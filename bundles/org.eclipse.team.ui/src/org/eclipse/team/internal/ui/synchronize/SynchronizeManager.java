/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.util.ListenerList;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.internal.ui.IPreferenceIds;
import org.eclipse.team.internal.ui.Policy;
import org.eclipse.team.internal.ui.TeamUIPlugin;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.registry.SynchronizeParticipantDescriptor;
import org.eclipse.team.internal.ui.registry.SynchronizeParticipantRegistry;
import org.eclipse.team.ui.ITeamUIConstants;
import org.eclipse.team.ui.synchronize.ISynchronizeManager;
import org.eclipse.team.ui.synchronize.ISynchronizeParticipant;
import org.eclipse.team.ui.synchronize.ISynchronizeParticipantDescriptor;
import org.eclipse.team.ui.synchronize.ISynchronizeParticipantListener;
import org.eclipse.team.ui.synchronize.ISynchronizeView;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.XMLMemento;

/**
 * Manages the registered synchronize participants. It handles notification
 * of participant lifecycles, creation of <code>static</code> participants, 
 * and the re-creation of persisted participants.
 * 
 * @see ISynchronizeView
 * @see ISynchronizeParticipant
 * @since 3.0
 */
public class SynchronizeManager implements ISynchronizeManager {
	/**
	 * Synchronize participants listeners
	 */
	private ListenerList fListeners = null;
	
	/**
	 * List of registered synchronize view pages
	 * {String id -> List participant instances}}
	 */
	private Map synchronizeParticipants = new HashMap(10); 
	private SynchronizeParticipantRegistry participantRegistry = new SynchronizeParticipantRegistry();
	
	// change notification constants
	private final static int ADDED = 1;
	private final static int REMOVED = 2;
	
	// save context constants
	private final static String CTX_PARTICIPANTS = "syncparticipants"; //$NON-NLS-1$
	private final static String CTX_PARTICIPANT = "participant"; //$NON-NLS-1$
	private final static String CTX_ID = "id"; //$NON-NLS-1$
	private final static String CTX_PARTICIPANT_DATA = "data"; //$NON-NLS-1$
	private final static String FILENAME = "syncParticipants.xml"; //$NON-NLS-1$
	
	/**
	 * Notifies a participant listeners of additions or removals
	 */
	class SynchronizeViewPageNotifier implements ISafeRunnable {
		
		private ISynchronizeParticipantListener fListener;
		private int fType;
		private ISynchronizeParticipant[] fChanged;
		
		/* (non-Javadoc)
		 * @see org.eclipse.core.runtime.ISafeRunnable#handleException(java.lang.Throwable)
		 */
		public void handleException(Throwable exception) {
			TeamUIPlugin.log(IStatus.ERROR, Policy.bind("SynchronizeManager.7"), exception); //$NON-NLS-1$
		}
		
		/* (non-Javadoc)
		 * @see org.eclipse.core.runtime.ISafeRunnable#run()
		 */
		public void run() throws Exception {
			switch (fType) {
				case ADDED:
					fListener.participantsAdded(fChanged);
					break;
				case REMOVED:
					fListener.participantsRemoved(fChanged);
					break;
			}
		}
		
		/**
		 * Notifies the given listener of the adds/removes
		 * 
		 * @param participants the participants that changed
		 * @param update the type of change
		 */
		public void notify(ISynchronizeParticipant[] participants, int update) {
			if (fListeners == null) {
				return;
			}
			fChanged = participants;
			fType = update;
			Object[] copiedListeners= fListeners.getListeners();
			for (int i= 0; i < copiedListeners.length; i++) {
				fListener = (ISynchronizeParticipantListener)copiedListeners[i];
				Platform.run(this);
			}	
			fChanged = null;
			fListener = null;			
		}
	}	
	
	public SynchronizeManager() {
		super();
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#addSynchronizeParticipantListener(org.eclipse.team.ui.sync.ISynchronizeParticipantListener)
	 */
	public void addSynchronizeParticipantListener(ISynchronizeParticipantListener listener) {
		if (fListeners == null) {
			fListeners = new ListenerList(5);
		}
		fListeners.add(listener);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#removeSynchronizeParticipantListener(org.eclipse.team.ui.sync.ISynchronizeParticipantListener)
	 */
	public void removeSynchronizeParticipantListener(ISynchronizeParticipantListener listener) {
		if (fListeners != null) {
			fListeners.remove(listener);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#addSynchronizeParticipants(org.eclipse.team.ui.sync.ISynchronizeParticipant[])
	 */
	public synchronized void addSynchronizeParticipants(ISynchronizeParticipant[] participants) {
		List added = new ArrayList(participants.length);
		for (int i = 0; i < participants.length; i++) {
			ISynchronizeParticipant participant = participants[i];
			addParticipant(participant);
			added.add(participant);
		}		
		if (!added.isEmpty()) {
			saveState();
			fireUpdate((ISynchronizeParticipant[])added.toArray(new ISynchronizeParticipant[added.size()]), ADDED);
		}
	}
	
	private synchronized void addParticipant(ISynchronizeParticipant participant) {
		String id = participant.getId();
		List instances = (List)synchronizeParticipants.get(id);
		if(instances == null) {
			instances = new ArrayList(2);
			synchronizeParticipants.put(id, instances);
		}
		if(! instances.contains(participant)) {
			instances.add(participant);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#removeSynchronizeParticipants(org.eclipse.team.ui.sync.ISynchronizeParticipant[])
	 */
	public synchronized void removeSynchronizeParticipants(ISynchronizeParticipant[] participants) {
		List removed = new ArrayList(participants.length);
		for (int i = 0; i < participants.length; i++) {
			ISynchronizeParticipant participant = participants[i];
			if (removeParticipant(participant)) {
				removed.add(participant);
			}
		}
		if (!removed.isEmpty()) {
			saveState();
			fireUpdate((ISynchronizeParticipant[])removed.toArray(new ISynchronizeParticipant[removed.size()]), REMOVED);
		}
	}
	
	private synchronized boolean removeParticipant(ISynchronizeParticipant participant) {
		boolean removed = false;
		String id = participant.getId();
		List instances = (List)synchronizeParticipants.get(id);
		if(instances != null) {
			removed = instances.remove(participant);
			if(instances.isEmpty()) {
				synchronizeParticipants.remove(id);
			}
		}
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#getSynchronizeParticipants()
	 */
	public synchronized ISynchronizeParticipant[] getSynchronizeParticipants() {
		List participants = new ArrayList();
		for (Iterator it = synchronizeParticipants.values().iterator(); it.hasNext();) {			
			List instances = (List) it.next();
			participants.addAll(instances);
		}
		return (ISynchronizeParticipant[]) participants.toArray(new ISynchronizeParticipant[participants.size()]);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.sync.ISynchronizeManager#find(java.lang.String)
	 */
	public ISynchronizeParticipant[] find(String id) {
		List participants = (List)synchronizeParticipants.get(id);
		if(participants == null) {
			return null;
		}
		return (ISynchronizeParticipant[]) participants.toArray(new ISynchronizeParticipant[participants.size()]);
	}
	
	/**
	 * Fires notification.
	 * 
	 * @param participants participants added/removed
	 * @param type ADD or REMOVE
	 */
	private void fireUpdate(ISynchronizeParticipant[] participants, int type) {
		new SynchronizeViewPageNotifier().notify(participants, type);
	}
	
	/**
	 * Called to display the synchronize view in the given page. If the given
	 * page is <code>null</code> the synchronize view is shown in the default
	 * active workbench window.
	 */
	public ISynchronizeView showSynchronizeViewInActivePage(IWorkbenchPage activePage) {
		IWorkbench workbench= TeamUIPlugin.getPlugin().getWorkbench();
		IWorkbenchWindow window= workbench.getActiveWorkbenchWindow();
		
		if(! TeamUIPlugin.getPlugin().getPreferenceStore().getString(IPreferenceIds.SYNCVIEW_DEFAULT_PERSPECTIVE).equals(IPreferenceIds.SYNCVIEW_DEFAULT_PERSPECTIVE_NONE)) {			
			try {
				String pId = TeamUIPlugin.getPlugin().getPreferenceStore().getString(IPreferenceIds.SYNCVIEW_DEFAULT_PERSPECTIVE);
				activePage = workbench.showPerspective(pId, window);
			} catch (WorkbenchException e) {
				Utils.handleError(window.getShell(), e, Policy.bind("SynchronizeView.14"), e.getMessage()); //$NON-NLS-1$
			}
		}
		try {
			if (activePage == null) {
				activePage = TeamUIPlugin.getActivePage();
				if (activePage == null) return null;
			}
			return (ISynchronizeView)activePage.showView(ISynchronizeView.VIEW_ID);
		} catch (PartInitException pe) {
			Utils.handleError(window.getShell(), pe, Policy.bind("SynchronizeView.16"), pe.getMessage()); //$NON-NLS-1$
			return null;
		}
	}
	
	/**
	 * Creates the participant registry and restore any saved participants. Will also instantiate
	 * any static participants.   
	 */
	public void initialize() {
		try {
			// Initialize the participant registry - reads all participant extension descriptions.
			participantRegistry.readRegistry(Platform.getPluginRegistry(), TeamUIPlugin.ID, ITeamUIConstants.PT_SYNCPARTICIPANTS);
			
			// Instantiate and register any dynamic participants saved from a previous session.
			restoreSavedParticipants();
			
			// Instantiate and register any static participant that has not already been created.			
			initializeStaticParticipants();
		} catch (CoreException e) { 
			TeamUIPlugin.log(new Status(IStatus.ERROR, TeamUIPlugin.ID, 1, Policy.bind("SynchronizeManager.8"), e)); //$NON-NLS-1$
		}
	}
	
	private void initializeStaticParticipants() throws CoreException {
		SynchronizeParticipantDescriptor[] desc = participantRegistry.getSynchronizeParticipants();
		List participants = new ArrayList();
		for (int i = 0; i < desc.length; i++) {
			SynchronizeParticipantDescriptor descriptor = desc[i];
			if(descriptor.isStatic() && ! synchronizeParticipants.containsKey(descriptor.getId())) {
				participants.add(createParticipant(null, descriptor));
			}
		}		
		if(! participants.isEmpty()) {
			addSynchronizeParticipants((ISynchronizeParticipant[]) participants.toArray(new ISynchronizeParticipant[participants.size()]));
		}
	}
	
	/**
	 * Restores participants that have been saved between sessions. 
	 */
	private void restoreSavedParticipants() throws TeamException, CoreException {
		File file = getStateFile();	
		Reader reader;
		try {
			reader = new BufferedReader(new FileReader(file));
		} catch (FileNotFoundException e) {
			return;
		}
		List participants = new ArrayList();
		IMemento memento = XMLMemento.createReadRoot(reader);
		IMemento[] participantNodes = memento.getChildren(CTX_PARTICIPANT);
		for (int i = 0; i < participantNodes.length; i++) {
			IMemento memento2 = participantNodes[i];			
			String id = memento2.getString(CTX_ID);
			SynchronizeParticipantDescriptor desc = participantRegistry.find(id);				
			if(desc != null) {
				IConfigurationElement cfgElement = desc.getConfigurationElement();
				participants.add(createParticipant(memento2.getChild(CTX_PARTICIPANT_DATA), desc));
			} else {
				TeamUIPlugin.log(new Status(IStatus.ERROR, TeamUIPlugin.ID, 1, Policy.bind("SynchronizeManager.9", id), null)); //$NON-NLS-1$
			}
		}
		if(! participants.isEmpty()) {
			addSynchronizeParticipants((ISynchronizeParticipant[]) participants.toArray(new ISynchronizeParticipant[participants.size()]));
		}
	}
	
	/**
	 * Creates a participant instance with the given id from the participant description
	 */
	private ISynchronizeParticipant createParticipant(IMemento memento, SynchronizeParticipantDescriptor desc) throws CoreException {
		ISynchronizeParticipant participant = (ISynchronizeParticipant)TeamUIPlugin.createExtension(desc.getConfigurationElement(), SynchronizeParticipantDescriptor.ATT_CLASS);
		participant.setInitializationData(desc.getConfigurationElement(), null, null);
		participant.restoreState(memento);
		return participant;
	}
	
	/**
	 * Saves a file containing the list of participant ids that are registered with this
	 * manager. Each participant is also given the chance to save it's state. 
	 */
	private void saveState() {
		XMLMemento xmlMemento = XMLMemento.createWriteRoot(CTX_PARTICIPANTS);	
		List children = new ArrayList();
		for (Iterator it = synchronizeParticipants.keySet().iterator(); it.hasNext();) {			
			String id = (String) it.next();
			List participants = (List)synchronizeParticipants.get(id);
			for (Iterator it2 = participants.iterator(); it2.hasNext(); ) {
				ISynchronizeParticipant participant = (ISynchronizeParticipant) it2.next();
				IMemento participantNode = xmlMemento.createChild(CTX_PARTICIPANT);
				participantNode.putString(CTX_ID, participant.getId());
				participant.saveState(participantNode.createChild(CTX_PARTICIPANT_DATA));
			}
		}
		try {
			Writer writer = new BufferedWriter(new FileWriter(getStateFile()));
			try {
				xmlMemento.save(writer);
			} finally {
				writer.close();
			}
		} catch (IOException e) {
			TeamUIPlugin.log(new Status(IStatus.ERROR, TeamUIPlugin.ID, 1, Policy.bind("SynchronizeManager.10"), e)); //$NON-NLS-1$
		}					
	}
	
	private File getStateFile() {
		IPath pluginStateLocation = TeamUIPlugin.getPlugin().getStateLocation();
		return pluginStateLocation.append(FILENAME).toFile(); //$NON-NLS-1$	
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.ISynchronizeManager#getParticipantDescriptor(java.lang.String)
	 */
	public ISynchronizeParticipantDescriptor getParticipantDescriptor(String id) {
		return participantRegistry.find(id);
	}
}