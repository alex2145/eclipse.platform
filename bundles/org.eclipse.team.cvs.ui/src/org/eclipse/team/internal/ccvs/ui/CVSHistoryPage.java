/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.team.internal.ccvs.ui;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.util.*;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.*;
import org.eclipse.jface.util.IOpenEventListener;
import org.eclipse.jface.util.OpenStrategy;
import org.eclipse.jface.viewers.*;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.history.*;
import org.eclipse.team.internal.ccvs.core.*;
import org.eclipse.team.internal.ccvs.core.client.Command;
import org.eclipse.team.internal.ccvs.core.client.Update;
import org.eclipse.team.internal.ccvs.core.filehistory.CVSFileHistory;
import org.eclipse.team.internal.ccvs.core.filehistory.CVSFileRevision;
import org.eclipse.team.internal.ccvs.core.resources.CVSWorkspaceRoot;
import org.eclipse.team.internal.ccvs.core.resources.RemoteFile;
import org.eclipse.team.internal.ccvs.ui.actions.*;
import org.eclipse.team.internal.ccvs.ui.operations.*;
import org.eclipse.team.internal.core.LocalFileRevision;
import org.eclipse.team.internal.ui.TeamUIMessages;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.history.*;
import org.eclipse.team.ui.history.*;
import org.eclipse.ui.*;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;

public class CVSHistoryPage extends HistoryPage implements IAdaptable, IHistoryCompareAdapter {
	
	/* private */ ICVSFile file;
	/* private */ IFileRevision currentFileRevision;
	
	// cached for efficiency
	/* private */ CVSFileHistory cvsFileHistory;
	/* private */IFileRevision[] entries;

	protected CVSHistoryTableProvider historyTableProvider;

	/* private */TreeViewer treeViewer;
	protected TextViewer textViewer;
	protected TableViewer tagViewer;

	/* private */CompareRevisionAction compareAction;
	/* private */OpenRevisionAction openAction;
	
	private CVSHistoryFilterAction  cvsHistoryFilter;
	private IAction toggleTextAction;
	private IAction toggleTextWrapAction;
	private IAction toggleListAction;
	private IAction toggleCompareAction;
	private IAction toggleFilterAction;
	private TextViewerAction copyAction;
	private TextViewerAction selectAllAction;
	private Action getContentsAction;
	private Action getRevisionAction;
	private Action refreshAction;
	
	private Action tagWithExistingAction;
	private Action localMode;
	private Action remoteMode;
	private Action remoteLocalMode;
	private Action groupByDateMode;
	private Action collapseAll;
	
	private SashForm sashForm;
	private SashForm innerSashForm;

	private Image branchImage;
	private Image versionImage;
	
	protected IFileRevision currentSelection;

	protected RefreshCVSFileHistory  refreshCVSFileHistoryJob;

	/* private */boolean shutdown = false;

	boolean localFilteredOut = false;
	boolean remoteFilteredOut = false;
	
	private HistoryResourceListener resourceListener;
	
	//toggle constants for default click action
	private boolean compareMode = false;
	
	//filter constants
	public final static int REMOTE_LOCAL_MODE = 0;
	public final static int REMOTE_MODE = 1;
	public final static int LOCAL_MODE = 2;
	
	//current filter mode
	private int currentFilerMode = 0;
	
	//grouping on
	private boolean groupingOn;
	private CVSHistoryFilter historyFilter; 
	
	public CVSHistoryPage(Object object) {
		this.file = getCVSFile(object);
	}

	public void createControl(Composite parent) {
		initializeImages();
		
		sashForm = new SashForm(parent, SWT.VERTICAL);
		sashForm.setLayoutData(new GridData(GridData.FILL_BOTH));

		treeViewer = createTree(sashForm);
		innerSashForm = new SashForm(sashForm, SWT.HORIZONTAL);
		tagViewer = createTagTable(innerSashForm);
		textViewer = createText(innerSashForm);
		sashForm.setWeights(new int[] {70, 30});
		innerSashForm.setWeights(new int[] {50, 50});

		contributeActions();

		setViewerVisibility();
		
		IHistoryPageSite parentSite = getHistoryPageSite();
		if (parentSite != null && parentSite instanceof DialogHistoryPageSite && treeViewer != null)
			parentSite.setSelectionProvider(treeViewer);
		
		resourceListener = new HistoryResourceListener();
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
	}

	private TextViewer createText(SashForm parent) {
		TextViewer result = new TextViewer(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.MULTI | SWT.BORDER | SWT.READ_ONLY);
		result.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				copyAction.update();
			}
		});
		return result;
	}

	private TableViewer createTagTable(SashForm parent) {
		Table table = new Table(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
		TableViewer result = new TableViewer(table);
		TableLayout layout = new TableLayout();
		layout.addColumnData(new ColumnWeightData(100));
		table.setLayout(layout);
		result.setContentProvider(new SimpleContentProvider() {
			public Object[] getElements(Object inputElement) {
				if (inputElement == null)
					return new Object[0];
				ITag[] tags = (ITag[]) inputElement;
				return tags;
			}
		});
		result.setLabelProvider(new LabelProvider() {
			public Image getImage(Object element) {
				if (element == null)
					return null;
				ITag tag = (ITag) element;
				if (!(tag instanceof CVSTag))
					return null;
				
				switch (((CVSTag)tag).getType()) {
					case CVSTag.BRANCH:
					case CVSTag.HEAD:
						return branchImage;
					case CVSTag.VERSION:
						return versionImage;
				}
				return null;
			}

			public String getText(Object element) {
				return ((ITag) element).getName();
			}
		});
		result.setSorter(new ViewerSorter() {
			public int compare(Viewer viewer, Object e1, Object e2) {
				if (!(e1 instanceof ITag) || !(e2 instanceof ITag))
					return super.compare(viewer, e1, e2);
				CVSTag tag1 = (CVSTag) e1;
				CVSTag tag2 = (CVSTag) e2;
				int type1 = tag1.getType();
				 int type2 = tag2.getType();
				 if (type1 != type2) {
				 return type2 - type1;
				 }
				return super.compare(viewer, tag1, tag2);
			}
		});
		return result;
	}

	public void setFocus() {
		sashForm.setFocus();
	}

	protected void contributeActions() {
		CVSUIPlugin plugin = CVSUIPlugin.getPlugin();

		//Refresh
		refreshAction = new Action(CVSUIMessages.HistoryView_refreshLabel, plugin.getImageDescriptor(ICVSUIConstants.IMG_REFRESH_ENABLED)) {
			public void run() {
				refresh();
			}
		};
		refreshAction.setToolTipText(CVSUIMessages.HistoryView_refresh); 
		refreshAction.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_REFRESH_DISABLED));
		refreshAction.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_REFRESH));

		//Local Mode
		final IPreferenceStore store = CVSUIPlugin.getPlugin().getPreferenceStore();
		localMode =  new Action(CVSUIMessages.CVSHistoryPage_LocalModeAction, plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALMODE)) {
			public void run() {
				if (isChecked()){
					store.setValue(ICVSUIConstants.PREF_REVISION_MODE, LOCAL_MODE);
					updateFilterMode(LOCAL_MODE);
				} else 
					setChecked(true);
			}
		};
		localMode.setToolTipText(CVSUIMessages.CVSHistoryPage_LocalModeTooltip); 
		localMode.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALMODE_DISABLED));
		localMode.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALMODE));

		//Remote Mode
		remoteMode =  new Action(CVSUIMessages.CVSHistoryPage_RemoteModeAction, plugin.getImageDescriptor(ICVSUIConstants.IMG_REMOTEMODE)) {
			public void run() {
				if (isChecked()){
					store.setValue(ICVSUIConstants.PREF_REVISION_MODE, REMOTE_MODE);
					updateFilterMode(REMOTE_MODE);
				} else
					setChecked(true);
			}
		};
		remoteMode.setToolTipText(CVSUIMessages.CVSHistoryPage_RemoteModeTooltip); 
		remoteMode.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_REMOTEMODE_DISABLED));
		remoteMode.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_REMOTEMODE));
		
		//Remote + Local Mode
		remoteLocalMode =  new Action(CVSUIMessages.CVSHistoryPage_CombinedModeAction, plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALREMOTE_MODE)) {
			public void run() {
				if (isChecked()){
					store.setValue(ICVSUIConstants.PREF_REVISION_MODE, REMOTE_LOCAL_MODE);
					updateFilterMode(REMOTE_LOCAL_MODE);
				} else
					setChecked(true);
			}
		};
		remoteLocalMode.setToolTipText(CVSUIMessages.CVSHistoryPage_CombinedModeTooltip); 
		remoteLocalMode.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALREMOTE_MODE_DISABLED));
		remoteLocalMode.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_LOCALREMOTE_MODE));
		
		//set the inital filter to both remote and local
		updateFilterMode(store.getInt(ICVSUIConstants.PREF_REVISION_MODE));
		
		//Group by Date
		groupByDateMode = new Action(CVSUIMessages.CVSHistoryPage_GroupByDate, CVSUIPlugin.getPlugin().getImageDescriptor(ICVSUIConstants.IMG_DATES_CATEGORY)){
			public void run() {
				groupingOn = !groupingOn;
				toggleCompareAction.setChecked(groupingOn);
				store.setValue(ICVSUIConstants.PREF_GROUPBYDATE_MODE, groupingOn);
				refreshHistory(false);
			}
		};
		groupingOn = store.getBoolean(ICVSUIConstants.PREF_GROUPBYDATE_MODE);
		groupByDateMode.setChecked(groupingOn);
		groupByDateMode.setToolTipText(CVSUIMessages.CVSHistoryPage_GroupByDate);
		groupByDateMode.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_DATES_CATEGORY));
		groupByDateMode.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_DATES_CATEGORY));
		
		//Collapse All
		collapseAll =  new Action(CVSUIMessages.CVSHistoryPage_CollapseAllAction, plugin.getImageDescriptor(ICVSUIConstants.IMG_COLLAPSE_ALL)) {
			public void run() {
				treeViewer.collapseAll();
			}
		};
		collapseAll.setToolTipText(CVSUIMessages.CVSHistoryPage_CollapseAllTooltip); 
		collapseAll.setDisabledImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_COLLAPSE_ALL));
		collapseAll.setHoverImageDescriptor(plugin.getImageDescriptor(ICVSUIConstants.IMG_COLLAPSE_ALL));
		
		
		// Click Compare action
		compareAction = new CompareRevisionAction(CVSUIMessages.CVSHistoryPage_CompareRevisionAction);
		treeViewer.getTree().addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e) {
				compareAction.selectionChanged((IStructuredSelection) treeViewer.getSelection());
			}
		});
		compareAction.setPage(this);
		
		openAction = new OpenRevisionAction(CVSUIMessages.CVSHistoryPage_OpenAction);
		treeViewer.getTree().addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e) {
				openAction.selectionChanged((IStructuredSelection) treeViewer.getSelection());
			}
		});
		openAction.setPage(this);
		
		OpenStrategy handler = new OpenStrategy(treeViewer.getTree());
		handler.addOpenListener(new IOpenEventListener() {
		public void handleOpen(SelectionEvent e) {
				StructuredSelection tableStructuredSelection = (StructuredSelection) treeViewer.getSelection();
				if (compareMode){
					StructuredSelection sel = new StructuredSelection(new Object[] {getCurrentFileRevision(), tableStructuredSelection.getFirstElement()});
					compareAction.selectionChanged(sel);
					compareAction.run();
				} else {
					//Pass in the entire structured selection to allow for multiple editor openings
					StructuredSelection sel = tableStructuredSelection;
					openAction.selectionChanged(sel);
					openAction.run();
				}
			}
		});

		getContentsAction = getContextMenuAction(CVSUIMessages.HistoryView_getContentsAction, true /* needs progress */, new IWorkspaceRunnable() { 
			public void run(IProgressMonitor monitor) throws CoreException {
				monitor.beginTask(null, 100);
				try {
					if(confirmOverwrite()) {
						IStorage currentStorage = currentSelection.getStorage(new SubProgressMonitor(monitor, 50));
						InputStream in = currentStorage.getContents();
						((IFile)file.getIResource()).setContents(in, false, true, new SubProgressMonitor(monitor, 50));				
					}
				} catch (TeamException e) {
					throw new CoreException(e.getStatus());
				} finally {
					monitor.done();
				}
			}
		});
        PlatformUI.getWorkbench().getHelpSystem().setHelp(getContentsAction, IHelpContextIds.GET_FILE_CONTENTS_ACTION);	

		getRevisionAction = getContextMenuAction(CVSUIMessages.HistoryView_getRevisionAction, true /* needs progress */, new IWorkspaceRunnable() { 
			public void run(IProgressMonitor monitor) throws CoreException {
				ICVSRemoteFile remoteFile = (ICVSRemoteFile) CVSWorkspaceRoot.getRemoteResourceFor(((CVSFileRevision) currentSelection).getCVSRemoteFile());
				try {
					if(confirmOverwrite()) {
						CVSTag revisionTag = new CVSTag(remoteFile.getRevision(), CVSTag.VERSION);
						
						if(CVSAction.checkForMixingTags(getSite().getShell(), new IResource[] {file.getIResource()}, revisionTag)) {
							new UpdateOperation(
									null, 
									new IResource[] {file.getIResource()},
									new Command.LocalOption[] {Update.IGNORE_LOCAL_CHANGES}, 
									revisionTag)
										.run(monitor);
							
							Display.getDefault().asyncExec(new Runnable() {
								public void run() {
									refresh();
								}
							});
						}
					}
				} catch (InvocationTargetException e) {
					CVSException.wrapException(e);
				} catch (InterruptedException e) {
					// Cancelled by user
				}
			}
		});
        PlatformUI.getWorkbench().getHelpSystem().setHelp(getRevisionAction, IHelpContextIds.GET_FILE_REVISION_ACTION);	

		// Override MoveRemoteTagAction to work for log entries
		final IActionDelegate tagActionDelegate = new MoveRemoteTagAction() {
			protected ICVSResource[] getSelectedCVSResources() {
				ICVSResource[] resources = super.getSelectedCVSResources();
				if (resources == null || resources.length == 0) {
					ArrayList logEntrieFiles = null;
					IStructuredSelection selection = getSelection();
					if (!selection.isEmpty()) {
						logEntrieFiles = new ArrayList();
						Iterator elements = selection.iterator();
						while (elements.hasNext()) {
							Object next = elements.next();
							if (next instanceof CVSFileRevision) {
								logEntrieFiles.add(((CVSFileRevision)next).getCVSRemoteFile());
								continue;
							}
							if (next instanceof IAdaptable) {
								IAdaptable a = (IAdaptable) next;
								Object adapter = a.getAdapter(ICVSResource.class);
								if (adapter instanceof ICVSResource) {
									logEntrieFiles.add(((ILogEntry)adapter).getRemoteFile());
									continue;
								}
							}
						}
					}
					if (logEntrieFiles != null && !logEntrieFiles.isEmpty()) {
						return (ICVSResource[])logEntrieFiles.toArray(new ICVSResource[logEntrieFiles.size()]);
					}
				}
				return resources;
			}
            /*
             * Override the creation of the tag operation in order to support
             * the refresh of the view after the tag operation completes
             */
            protected ITagOperation createTagOperation() {
                return new TagInRepositoryOperation(getTargetPart(), getSelectedRemoteResources()) {
                    public void execute(IProgressMonitor monitor) throws CVSException, InterruptedException {
                        super.execute(monitor);
                        Display.getDefault().asyncExec(new Runnable() {
                            public void run() {
                                if( ! wasCancelled()) {
                                    refresh();
                                }
                            }
                        });
                    };
                };
            }
		};
		tagWithExistingAction = getContextMenuAction(CVSUIMessages.HistoryView_tagWithExistingAction, false /* no progress */, new IWorkspaceRunnable() { 
			public void run(IProgressMonitor monitor) throws CoreException {
				tagActionDelegate.selectionChanged(tagWithExistingAction, treeViewer.getSelection());
				tagActionDelegate.run(tagWithExistingAction);
			}
		});
        PlatformUI.getWorkbench().getHelpSystem().setHelp(getRevisionAction, IHelpContextIds.TAG_WITH_EXISTING_ACTION);	
        
		// Toggle text visible action
		toggleTextAction = new Action(TeamUIMessages.GenericHistoryView_ShowCommentViewer) {
			public void run() {
				setViewerVisibility();
				store.setValue(ICVSUIConstants.PREF_SHOW_COMMENTS, toggleTextAction.isChecked());
			}
		};
		toggleTextAction.setChecked(store.getBoolean(ICVSUIConstants.PREF_SHOW_COMMENTS));
		//PlatformUI.getWorkbench().getHelpSystem().setHelp(toggleTextAction, IHelpContextIds.SHOW_COMMENT_IN_HISTORY_ACTION);	

		// Toggle wrap comments action
		toggleTextWrapAction = new Action(TeamUIMessages.GenericHistoryView_WrapComments) {
			public void run() {
				setViewerVisibility();
				store.setValue(ICVSUIConstants.PREF_WRAP_COMMENTS, toggleTextWrapAction.isChecked());
			}
		};
		toggleTextWrapAction.setChecked(store.getBoolean(ICVSUIConstants.PREF_WRAP_COMMENTS));
		//PlatformUI.getWorkbench().getHelpSystem().setHelp(toggleTextWrapAction, IHelpContextIds.SHOW_TAGS_IN_HISTORY_ACTION);   

		// Toggle list visible action
		toggleListAction = new Action(TeamUIMessages.GenericHistoryView_ShowTagViewer) {
			public void run() {
				setViewerVisibility();
				store.setValue(ICVSUIConstants.PREF_SHOW_TAGS, toggleListAction.isChecked());
			}
		};
		toggleListAction.setChecked(store.getBoolean(ICVSUIConstants.PREF_SHOW_TAGS));
		//PlatformUI.getWorkbench().getHelpSystem().setHelp(toggleListAction, IHelpContextIds.SHOW_TAGS_IN_HISTORY_ACTION);	

		toggleFilterAction = new Action(CVSUIMessages.CVSHistoryPage_NoFilter){
			public void run(){
				if (historyFilter != null)
					treeViewer.removeFilter(historyFilter);
					historyFilter = null;
					toggleFilterAction.setEnabled(false);
			}
		};
		toggleFilterAction.setEnabled(historyFilter != null);
		
		
		toggleCompareAction = new Action(CVSUIMessages.CVSHistoryPage_CompareModeToggleAction){
			public void run() {
				compareMode = !compareMode;
				toggleCompareAction.setChecked(compareMode);
			}
		};
		toggleCompareAction.setChecked(false);
	
		//Create the filter action
		cvsHistoryFilter = new CVSHistoryFilterAction(this);
		cvsHistoryFilter.setText(CVSUIMessages.CVSHistoryPage_FilterOn);
		cvsHistoryFilter.init(treeViewer);
		cvsHistoryFilter.setToolTipText(CVSUIMessages.CVSHistoryPage_FilterHistoryTooltip);
		
		//Contribute actions to popup menu
		MenuManager menuMgr = new MenuManager();
		Menu menu = menuMgr.createContextMenu(treeViewer.getTree());
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager menuMgr) {
				fillTableMenu(menuMgr);
			}
		});
		menuMgr.setRemoveAllWhenShown(true);
		treeViewer.getTree().setMenu(menu);
		//Don't add the object contribution menu items if this page is hosted in a dialog
		IHistoryPageSite parentSite = getHistoryPageSite();
		if (!parentSite.isModal()) {
			IWorkbenchPart part = parentSite.getPart();
			if (part != null) {
				IWorkbenchPartSite workbenchPartSite = part.getSite();
				workbenchPartSite.registerContextMenu(menuMgr, treeViewer);
			}
			IPageSite pageSite = parentSite.getWorkbenchPageSite();
			if (pageSite != null) {
				IActionBars actionBars = pageSite.getActionBars();
				// Contribute toggle text visible to the toolbar drop-down
				IMenuManager actionBarsMenu = actionBars.getMenuManager();
				if (actionBarsMenu != null){
					actionBarsMenu.add(toggleCompareAction);
					actionBarsMenu.add(new Separator());
					actionBarsMenu.add(toggleTextWrapAction);
					actionBarsMenu.add(new Separator());
					actionBarsMenu.add(toggleTextAction);
					actionBarsMenu.add(toggleListAction);
					actionBarsMenu.add(new Separator());
					actionBarsMenu.add(cvsHistoryFilter);
					actionBarsMenu.add(toggleFilterAction);
				}
				// Create actions for the text editor
				copyAction = new TextViewerAction(textViewer, ITextOperationTarget.COPY);
				copyAction.setText(CVSUIMessages.HistoryView_copy); 
				actionBars.setGlobalActionHandler(ITextEditorActionConstants.COPY, copyAction);
				
				selectAllAction = new TextViewerAction(textViewer, ITextOperationTarget.SELECT_ALL);
				selectAllAction.setText(CVSUIMessages.HistoryView_selectAll); 
				actionBars.setGlobalActionHandler(ITextEditorActionConstants.SELECT_ALL, selectAllAction);
				
				actionBars.updateActionBars();
			}
		}
		
		
		
		//Create the local tool bar
		IToolBarManager tbm = parentSite.getToolBarManager();
		if (tbm != null) {
			//Add groups
			tbm.add(new Separator("grouping"));	//$NON-NLS-1$
			tbm.appendToGroup("grouping", groupByDateMode); //$NON-NLS-1$
			tbm.add(new Separator("modes"));	//$NON-NLS-1$
			tbm.appendToGroup("modes", remoteLocalMode); //$NON-NLS-1$
			tbm.appendToGroup("modes", localMode); //$NON-NLS-1$
			tbm.appendToGroup("modes", remoteMode); //$NON-NLS-1$
			tbm.add(new Separator("collapse")); //$NON-NLS-1$
			tbm.appendToGroup("collapse", collapseAll); //$NON-NLS-1$
			tbm.update(false);
		}

		menuMgr = new MenuManager();
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager menuMgr) {
				fillTextMenu(menuMgr);
			}
		});
		StyledText text = textViewer.getTextWidget();
		menu = menuMgr.createContextMenu(text);
		text.setMenu(menu);
	}

	private boolean isLocalHistoryFilteredOut() {
		return localFilteredOut;
	}
	
	private boolean isRemoteHistoryFilteredOut(){
		return remoteFilteredOut;
	}
	
	/* private */ void fillTableMenu(IMenuManager manager) {
		// file actions go first (view file)
		IHistoryPageSite parentSite = getHistoryPageSite();
		manager.add(new Separator(IWorkbenchActionConstants.GROUP_FILE));
		
		if (file != null && !parentSite.isModal()){
			manager.add(openAction);
			manager.add(compareAction);
			manager.add(new Separator("openCompare")); //$NON-NLS-1$
		}
		if (file != null &&
		  !(file instanceof RemoteFile)) {
			// Add the "Add to Workspace" action if 1 revision is selected.
			ISelection sel = treeViewer.getSelection();
			if (!sel.isEmpty()) {
				if (sel instanceof IStructuredSelection) {
					IStructuredSelection tempSelection = (IStructuredSelection) sel;
					if (tempSelection.size() == 1) {
						manager.add(getContentsAction);
						if (!(tempSelection.getFirstElement() instanceof LocalFileRevision)) {
							manager.add(getRevisionAction);
							manager.add(new Separator());
							if (!parentSite.isModal())
								manager.add(tagWithExistingAction);
						}
					}
				}
			}
		}
		
		if (!parentSite.isModal()){
			manager.add(new Separator("additions")); //$NON-NLS-1$
			manager.add(refreshAction);
			manager.add(new Separator("additions-end")); //$NON-NLS-1$
		}
	}

	private void fillTextMenu(IMenuManager manager) {
		manager.add(copyAction);
		manager.add(selectAllAction);
	}
	
	/**
	 * Creates the group that displays lists of the available repositories and
	 * team streams.
	 * 
	 * @param the
	 *            parent composite to contain the group
	 * @return the group control
	 */
	protected TreeViewer createTree(Composite parent) {

		historyTableProvider = new CVSHistoryTableProvider();
		TreeViewer viewer = historyTableProvider.createTree(parent);

		viewer.setContentProvider(new ITreeContentProvider() {
			public Object[] getElements(Object inputElement) {

				// The entries of already been fetch so return them
				if (entries != null)
					return entries;
				
				if (!(inputElement instanceof IFileHistory) &&
					!(inputElement instanceof AbstractCVSHistoryCategory[]))
					return new Object[0];

				if (inputElement instanceof AbstractCVSHistoryCategory[]){
					return (AbstractCVSHistoryCategory[]) inputElement;
				}
				
				final IFileHistory fileHistory = (IFileHistory) inputElement;
				entries = fileHistory.getFileRevisions();
				
				return entries;
			}

			public void dispose() {
			}

			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				entries = null;
			}

			public Object[] getChildren(Object parentElement) {
				if (parentElement instanceof AbstractCVSHistoryCategory){
					return ((AbstractCVSHistoryCategory) parentElement).getRevisions();
				}
				
				return null;
			}

			public Object getParent(Object element) {
				return null;
			}

			public boolean hasChildren(Object element) {
				if (element instanceof AbstractCVSHistoryCategory){
					IFileRevision[] revs = ((AbstractCVSHistoryCategory) element).getRevisions();
					if (revs != null)
						return revs.length > 0;
				}
				return false;
			}
		});

		viewer.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				ISelection selection = event.getSelection();
				if (selection == null || !(selection instanceof IStructuredSelection)) {
					textViewer.setDocument(new Document("")); //$NON-NLS-1$
					tagViewer.setInput(null);
					return;
				}
				IStructuredSelection ss = (IStructuredSelection)selection;
				if (ss.size() != 1) {
					textViewer.setDocument(new Document("")); //$NON-NLS-1$
					tagViewer.setInput(null);
					return;
				}
				Object o = ss.getFirstElement();
				if (o instanceof AbstractCVSHistoryCategory){
					textViewer.setDocument(new Document("")); //$NON-NLS-1$
					tagViewer.setInput(null);
					return;
				}
				IFileRevision entry = (IFileRevision)o;
				textViewer.setDocument(new Document(entry.getComment()));
				tagViewer.setInput(entry.getTags());
			}
		});

		return viewer;
	}

	private Action getContextMenuAction(String title, final boolean needsProgressDialog, final IWorkspaceRunnable action) {
		return new Action(title) {
			public void run() {
				try {
					if (file == null) return;
					ISelection selection = treeViewer.getSelection();
					if (!(selection instanceof IStructuredSelection)) return;
					IStructuredSelection ss = (IStructuredSelection)selection;
					Object o = ss.getFirstElement();
					
					if (o instanceof AbstractCVSHistoryCategory)
						return;
					
					currentSelection = (IFileRevision)o;
					if(needsProgressDialog) {
						PlatformUI.getWorkbench().getProgressService().run(true, true, new IRunnableWithProgress() {
							public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
								try {				
									action.run(monitor);
								} catch (CoreException e) {
									throw new InvocationTargetException(e);
								}
							}
						});
					} else {
						try {				
							action.run(null);
						} catch (CoreException e) {
							throw new InvocationTargetException(e);
						}
					}							
				} catch (InvocationTargetException e) {
					IHistoryPageSite parentSite = getHistoryPageSite();
					CVSUIPlugin.openError(parentSite.getShell(), null, null, e, CVSUIPlugin.LOG_NONTEAM_EXCEPTIONS);
				} catch (InterruptedException e) {
					// Do nothing
				}
			}
			
			public boolean isEnabled() {
				ISelection selection = treeViewer.getSelection();
				if (!(selection instanceof IStructuredSelection)) return false;
				IStructuredSelection ss = (IStructuredSelection)selection;
				if(ss.size() != 1) return false;
				return true;
			}
		};
	}

	private boolean confirmOverwrite() {
		if (file!=null && file.getIResource().exists()) {
			try {
				if(file.isModified(null)) {
					String title = CVSUIMessages.HistoryView_overwriteTitle; 
					String msg = CVSUIMessages.HistoryView_overwriteMsg; 
					IHistoryPageSite parentSite = getHistoryPageSite();
					final MessageDialog dialog = new MessageDialog(parentSite.getShell(), title, null, msg, MessageDialog.QUESTION, new String[] { IDialogConstants.YES_LABEL, IDialogConstants.CANCEL_LABEL }, 0);
					final int[] result = new int[1];
					parentSite.getShell().getDisplay().syncExec(new Runnable() {
					public void run() {
						result[0] = dialog.open();
					}});
					if (result[0] != 0) {
						// cancel
						return false;
					}
				}
			} catch(CVSException e) {
				CVSUIPlugin.log(e);
			}
		}
		return true;
	}

	/*
	 * Refresh the view by refetching the log entries for the remote file
	 */
	public void refresh() {	
		refreshHistory(true);
	}

	private void refreshHistory(boolean refetch) {
		if (refreshCVSFileHistoryJob.getState() != Job.NONE){
			refreshCVSFileHistoryJob.cancel();
		}
		refreshCVSFileHistoryJob.setFileHistory(cvsFileHistory);
		refreshCVSFileHistoryJob.setRefetchHistory(refetch);
		refreshCVSFileHistoryJob.setIncludeLocals(!isLocalHistoryFilteredOut());
		refreshCVSFileHistoryJob.setIncludeRemote(!isRemoteHistoryFilteredOut());
		refreshCVSFileHistoryJob.setGrouping(groupingOn);
		IHistoryPageSite parentSite = getHistoryPageSite();
		Utils.schedule(refreshCVSFileHistoryJob, getWorkbenchSite(parentSite));
	}

	private IWorkbenchPartSite getWorkbenchSite(IHistoryPageSite parentSite) {
		IWorkbenchPart part = parentSite.getPart();
		if (part != null)
			return part.getSite();
		return null;
	}

	/**
	 * Select the revision in the receiver.
	 */
	public void selectRevision(String revision) {
		if (entries == null) {
			return;
		}
	
		IFileRevision entry = null;
		for (int i = 0; i < entries.length; i++) {
			if (entries[i].getContentIdentifier().equals(revision)) {
				entry = entries[i];
				break;
			}
		}
	
		if (entry != null) {
			IStructuredSelection selection = new StructuredSelection(entry);
			treeViewer.setSelection(selection, true);
		}
	}
	
	private ICVSFile getCVSFile(Object object) {
		if (object instanceof IFile){
			return CVSWorkspaceRoot.getCVSFileFor((IFile) object);
		} else if (object instanceof ICVSRemoteFile){
			return (ICVSRemoteFile) object;
		} else if (object instanceof CVSFileRevision){
			return ((CVSFileRevision) object).getCVSRemoteFile();
		}
		
		return null;
	}

	/* private */void setViewerVisibility() {
		boolean showText = toggleTextAction.isChecked();
		boolean showList = toggleListAction.isChecked();
		
		//check to see if this page is being shown in a dialog, in which case
		//don't show the text and list panes
		IHistoryPageSite parentSite = getHistoryPageSite();
		if (parentSite.isModal()){
			showText = false;
			showList = false;
		}
		
		if (showText && showList) {
			sashForm.setMaximizedControl(null);
			innerSashForm.setMaximizedControl(null);
		} else if (showText) {
			sashForm.setMaximizedControl(null);
			innerSashForm.setMaximizedControl(textViewer.getTextWidget());
		} else if (showList) {
			sashForm.setMaximizedControl(null);
			innerSashForm.setMaximizedControl(tagViewer.getTable());
		} else {
			sashForm.setMaximizedControl(treeViewer.getControl());
		}

		boolean wrapText = toggleTextWrapAction.isChecked();
		textViewer.getTextWidget().setWordWrap(wrapText);
	}

	private void initializeImages() {
		CVSUIPlugin plugin = CVSUIPlugin.getPlugin();
		versionImage = plugin.getImageDescriptor(ICVSUIConstants.IMG_PROJECT_VERSION).createImage();
		branchImage = plugin.getImageDescriptor(ICVSUIConstants.IMG_TAG).createImage();
	}
	
	public void dispose() {
		shutdown = true;

		if (resourceListener != null){
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
			resourceListener = null;
		}
		
		if (branchImage != null) {
			branchImage.dispose();
			branchImage = null;
		}
		if (versionImage != null) {
			versionImage.dispose();
			versionImage = null;
		}
		
		//Cancel any incoming 
		if (refreshCVSFileHistoryJob != null) {
			if (refreshCVSFileHistoryJob.getState() != Job.NONE) {
				refreshCVSFileHistoryJob.cancel();
			}
		}
	}

	public IFileRevision getCurrentFileRevision() {
		if (currentFileRevision != null)
			return currentFileRevision;

		if (file != null) {
			try {
				//Case 1 : file is remote  
				if (file instanceof RemoteFile) {
					RemoteFile remote = (RemoteFile) file;
					currentFileRevision = cvsFileHistory.getFileRevision(remote.getContentIdentifier());
					//remote.getContents(monitor);
					//currentFileRevision = new CVSFileRevision(remote.getLogEntry(monitor));
					return currentFileRevision;
				}
				//Case 2 : file is local
				//if (file.isModified(monitor)) {
				//file has been modified locally
				IFile localFile = (IFile) file.getIResource();
				if (localFile != null) {
					//make sure that there's actually a resource associated with the file
					currentFileRevision = new LocalFileRevision(localFile);
				} else {
					//no local version exists
					if (file.getSyncInfo() != null) {
						currentFileRevision = cvsFileHistory.getFileRevision(file.getSyncInfo().getRevision());
					}
				}
				return currentFileRevision;
			} catch (CVSException e) {
			} 
		}

		return null;
	}
	
	private class RefreshCVSFileHistory extends Job {
		private final static int NUMBER_OF_CATEGORIES = 4;
		
		private CVSFileHistory fileHistory;
		private AbstractCVSHistoryCategory[] categories;
		private boolean grouping;
		private Object[] elementsToExpand;
		private boolean revisionsFound;
		
		public RefreshCVSFileHistory() {
			super(CVSUIMessages.HistoryView_fetchHistoryJob);
		}
		
		public void setIncludeLocals(boolean flag) {
			if (fileHistory != null)
				fileHistory.includeLocalRevisions(flag);
		}
		
		public void setIncludeRemote(boolean flag){
			if (fileHistory != null)
				fileHistory.includeRemoteRevisions(flag);
		}

		public void setRefetchHistory(boolean refetch) {
			if (fileHistory != null)
				fileHistory.setRefetchRevisions(refetch);
			
		}

		public void setFileHistory(CVSFileHistory fileHistory) {
			this.fileHistory = fileHistory;
		}
	
		public void setGrouping (boolean value){
			this.grouping = value;
		}

		public IStatus run(IProgressMonitor monitor)  {
			try {
				if (fileHistory != null && !shutdown) {
					//If fileHistory termintates in a bad way, try to fetch the local
					//revisions only
					if (!fileHistory.refresh(monitor)){
						fileHistory.fetchLocalOnly(monitor);
					}
					
					if (grouping)
						revisionsFound = sortRevisions();
					
					Utils.asyncExec(new Runnable() {
							public void run() {
								historyTableProvider.setLocalRevisionsDisplayed(fileHistory.getIncludesExists());
								historyTableProvider.setFile(fileHistory, file);
								if (grouping){
									mapExpandedElements(treeViewer.getExpandedElements());
									treeViewer.getTree().setLinesVisible(revisionsFound);
									treeViewer.getTree().setRedraw(false);
									treeViewer.setInput(categories);
									//if user is switching modes and already has expanded elements
									//selected try to expand those, else expand all
									if (elementsToExpand.length > 0)
										treeViewer.setExpandedElements(elementsToExpand);
									else {
										treeViewer.expandAll();
										Object[] el = treeViewer.getExpandedElements();
										if (el != null && el.length > 0){
											treeViewer.setSelection(new StructuredSelection(el[0]));
											treeViewer.getTree().deselectAll();
										}
									}
									treeViewer.getTree().setRedraw(true);
								}
								else {
									if (fileHistory.getFileRevisions().length > 0){
										treeViewer.getTree().setLinesVisible(true);
										treeViewer.setInput(fileHistory);
									}
									else {
										categories = new AbstractCVSHistoryCategory[]{getErrorMessage()};
										treeViewer.getTree().setLinesVisible(false);
										treeViewer.setInput(categories);
									}
								}
							}
						}, treeViewer);
				}
				return Status.OK_STATUS;
			} catch (TeamException e) {
				return e.getStatus();
			} 
		}

		private void mapExpandedElements(Object[] expandedElements) {
			//store the names of the currently expanded categories in a map
			HashMap elementMap = new HashMap();
			for (int i=0; i<expandedElements.length; i++){
				elementMap.put(((DateCVSHistoryCategory)expandedElements[i]).getName(), null);
			}
			
			//Go through the new categories and keep track of the previously expanded ones
			ArrayList expandable = new ArrayList();
			for (int i = 0; i<categories.length; i++){
				//check to see if this category is currently expanded
				if (elementMap.containsKey(categories[i].getName())){
					expandable.add(categories[i]);
				}
			}
			
			elementsToExpand = new Object[expandable.size()];
			elementsToExpand = (Object[]) expandable.toArray(new Object[expandable.size()]);
		}

		private boolean sortRevisions() {
			IFileRevision[] fileRevision = fileHistory.getFileRevisions();
			
			//Create the 4 categories
			DateCVSHistoryCategory[] tempCategories = new DateCVSHistoryCategory[NUMBER_OF_CATEGORIES];
			//Get a calendar instance initialized to the current time
			Calendar currentCal = Calendar.getInstance();
			tempCategories[0] = new DateCVSHistoryCategory(CVSUIMessages.CVSHistoryPage_Today, currentCal, null);
			//Get yesterday 
			Calendar yesterdayCal = Calendar.getInstance();
			yesterdayCal.roll(Calendar.DAY_OF_YEAR, -1);
			tempCategories[1] = new DateCVSHistoryCategory(CVSUIMessages.CVSHistoryPage_Yesterday, yesterdayCal, null);
			//Get last week
			Calendar lastWeekCal = Calendar.getInstance();
			lastWeekCal.roll(Calendar.DAY_OF_YEAR, -7);
			tempCategories[2] = new DateCVSHistoryCategory(CVSUIMessages.CVSHistoryPage_LastWeek, lastWeekCal, yesterdayCal);
			//Everything before after week is previous
			tempCategories[3] = new DateCVSHistoryCategory(CVSUIMessages.CVSHistoryPage_Previous, null, lastWeekCal);
		
			ArrayList finalCategories = new ArrayList();
			for (int i = 0; i<NUMBER_OF_CATEGORIES; i++){
				tempCategories[i].collectFileRevisions(fileRevision, false);
				if (tempCategories[i].hasRevisions())
					finalCategories.add(tempCategories[i]);
			}
			
			//Assume that some revisions have been found
			boolean revisionsFound = true;
			
			if (finalCategories.size() == 0){
				//no revisions found for the current mode, so add a message category
				finalCategories.add(getErrorMessage());
				revisionsFound = false;
			}
			
			categories = (AbstractCVSHistoryCategory[])finalCategories.toArray(new AbstractCVSHistoryCategory[finalCategories.size()]);
			return revisionsFound;
		}
		
		private MessageCVSHistoryCategory getErrorMessage(){
			String message = ""; //$NON-NLS-1$
			switch(currentFilerMode){
				case LOCAL_MODE:
				message = CVSUIMessages.CVSHistoryPage_LocalModeTooltip;
				break;
				
				case REMOTE_MODE:
				message = CVSUIMessages.CVSHistoryPage_RemoteModeTooltip;
				break;
				
				case REMOTE_LOCAL_MODE:
				message = CVSUIMessages.CVSHistoryPage_NoRevisions;
				break;
			}
		 
			MessageCVSHistoryCategory messageCategory = new MessageCVSHistoryCategory(NLS.bind(CVSUIMessages.CVSHistoryPage_NoRevisionsForMode, new String[] { message }));
			return messageCategory;
		}
	}
	
	
	/**
	 * A default content provider to prevent subclasses from
	 * having to implement methods they don't need.
	 */
	private class SimpleContentProvider implements IStructuredContentProvider {

		/**
		 * SimpleContentProvider constructor.
		 */
		public SimpleContentProvider() {
			super();
		}

		/*
		 * @see SimpleContentProvider#dispose()
		 */
		public void dispose() {
		}

		/*
		 * @see SimpleContentProvider#getElements()
		 */
		public Object[] getElements(Object element) {
			return new Object[0];
		}

		/*
		 * @see SimpleContentProvider#inputChanged()
		 */
		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		}
	}

	private class HistoryResourceListener implements IResourceChangeListener {
		/**
		 * @see IResourceChangeListener#resourceChanged(IResourceChangeEvent)
		 */
		public void resourceChanged(IResourceChangeEvent event) {
			IResourceDelta root = event.getDelta();
			//Safety check for non-managed files that are added with the CVSHistoryPage
			//in view
			if (file == null ||	file.getIResource() == null)
				 return;
			
			IResourceDelta resourceDelta = root.findMember(((IFile)file.getIResource()).getFullPath());
			if (resourceDelta != null){
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						refresh();
					}
				});
			}
		}
	}
		
	public Control getControl() {
		return sashForm;
	}

	public boolean isValidInput(Object object) {
		if (object instanceof IResource){
		RepositoryProvider provider = RepositoryProvider.getProvider(((IResource)object).getProject());
		if (provider instanceof CVSTeamProvider)
			return true;
		} else if (object instanceof ICVSRemoteResource){
			return true;
		}
		
		return false;
	}

	public String getName() {
		if (file != null)
			return file.getName();
		
		return ""; //$NON-NLS-1$
	}

	/*
	 * Used to reset sorting in CVSHistoryTableProvider for
	 * changes to local revisions displays. Local revisions don't
	 * have a revision id so we need to sort by date when they are
	 * displayed - else we can just sort by revision id.
	 */
	public void setSorter(boolean localDisplayed) {
		historyTableProvider.setLocalRevisionsDisplayed(localDisplayed);
	}

	public Object getAdapter(Class adapter) {
		if(adapter == IHistoryCompareAdapter.class) {
			return this;
		}
		return null;
	}

	public ICompareInput getCompareInput(Object object) {
		
		if (object != null && object instanceof IStructuredSelection) {
			IStructuredSelection ss= (IStructuredSelection) object;
			if (ss.size() == 1) {
				Object o = ss.getFirstElement();
				if (o instanceof IFileRevision){
					IFileRevision selectedFileRevision = (IFileRevision)o;
					TypedBufferedContent left = new TypedBufferedContent((IFile) file.getIResource());
					FileRevisionTypedElement right = new FileRevisionTypedElement(selectedFileRevision);
					DiffNode node = new DiffNode(left,right);
					return node;
				}
			}
		}
		return null;
	}

	public void setClickAction(boolean compare) {
		//toggleCompareAction is going to switch the mode
		//so make sure that we're in the appropriate mode before
		compareMode = !compare;
		toggleCompareAction.run();
	}

	public void prepareInput(ICompareInput input, CompareConfiguration configuration, IProgressMonitor monitor) {
		initLabels(input, configuration);
		// TODO: pre-fetch contents
	}
	
	private void initLabels(ICompareInput input, CompareConfiguration cc) {
		cc.setLeftEditable(false);
		cc.setRightEditable(false);
		String leftLabel = getFileRevisionLabel(input.getLeft(), cc);
		cc.setLeftLabel(leftLabel);
		String rightLabel = getFileRevisionLabel(input.getRight(), cc);
		cc.setRightLabel(rightLabel);
	}

	private String getFileRevisionLabel(ITypedElement element, CompareConfiguration cc) {
		String label = null;

		if (element instanceof TypedBufferedContent) {
			//current revision
			Date dateFromLong = new Date(((TypedBufferedContent) element).getModificationDate());
			label = NLS.bind(TeamUIMessages.CompareFileRevisionEditorInput_workspace, new Object[]{ element.getName(), DateFormat.getDateTimeInstance().format(dateFromLong)});
			cc.setLeftEditable(true);
			return label;

		} else if (element instanceof FileRevisionTypedElement) {
			Object fileObject = ((FileRevisionTypedElement) element).getFileRevision();

			if (fileObject instanceof LocalFileRevision) {
				try {
					IStorage storage = ((LocalFileRevision) fileObject).getStorage(new NullProgressMonitor());
					if (Utils.getAdapter(storage, IFileState.class) != null) {
						//local revision
						label = NLS.bind(TeamUIMessages.CompareFileRevisionEditorInput_localRevision, new Object[]{element.getName(), ((FileRevisionTypedElement) element).getTimestamp()});
					}
				} catch (CoreException e) {
				}
			} else {
				label = NLS.bind(TeamUIMessages.CompareFileRevisionEditorInput_repository, new Object[]{ element.getName(), ((FileRevisionTypedElement) element).getContentIdentifier()});
			}
		}
		return label;
	}

	public String getDescription() {
		try {
			if (file != null)
				return file.getRepositoryRelativePath();
		} catch (CVSException e) {
			// Ignore
		}
		return null;
	}

	public boolean inputSet() {
		//reset currentFileRevision
		currentFileRevision = null;
		ICVSFile cvsFile = getCVSFile(getInput());
		this.file = cvsFile;
		if (cvsFile == null)
			return false;
		
		//blank current input only after we're sure that we have a file
		//to fetch history for
		this.treeViewer.setInput(null);
		
		cvsFileHistory = new CVSFileHistory(cvsFile);
		//fetch both local and remote revisions the first time around
		cvsFileHistory.includeLocalRevisions(true);
		if (refreshCVSFileHistoryJob == null)
			refreshCVSFileHistoryJob = new RefreshCVSFileHistory();
		
		//always refresh the history if the input gets set
		refreshHistory(true);
		return true;
	}
	
	private void updateFilterMode(int mode) {
		currentFilerMode=mode;
		switch(mode){
			case LOCAL_MODE:
				localFilteredOut = false;
				remoteFilteredOut = true;
				localMode.setChecked(true);
				remoteMode.setChecked(false);
				remoteLocalMode.setChecked(false);
				break;

			case REMOTE_MODE:
				localFilteredOut = true;
				remoteFilteredOut = false;
				localMode.setChecked(false);
				remoteMode.setChecked(true);
				remoteLocalMode.setChecked(false);
				break;

			case REMOTE_LOCAL_MODE:
				localFilteredOut = false;
				remoteFilteredOut = false;
				localMode.setChecked(false);
				remoteMode.setChecked(false);
				remoteLocalMode.setChecked(true);
				break;
		}

		//the refresh job gets created once the input is set
		//don't bother trying to refresh any history until the input has been set
		if (refreshCVSFileHistoryJob != null)
			refreshHistory(false);
	}

	public TreeViewer getTreeViewer() {
		return treeViewer;
	}

	public void showFilter(CVSHistoryFilter filter) {
		if (historyFilter != null)
			treeViewer.removeFilter(historyFilter);
		
		historyFilter = filter;
		treeViewer.addFilter(filter);
		toggleFilterAction.setEnabled(true);
	}
	
	/*
	 * Sets the filter mode for the page.
	 * param flag	LOCAL_MODE, REMOTE_MODE, REMOTE_LOCAL_MODE
	 */
	public void setMode(int flag){
		switch(flag){
			case LOCAL_MODE:
				localMode.setChecked(true);
				localMode.run();
				break;
			
			case REMOTE_MODE:
				remoteMode.setChecked(true);
				remoteMode.run();
				break;
				
			case REMOTE_LOCAL_MODE:
				remoteLocalMode.setChecked(true);
				remoteLocalMode.run();
				break;
		}
		
		refreshHistory(true);
	}
}
