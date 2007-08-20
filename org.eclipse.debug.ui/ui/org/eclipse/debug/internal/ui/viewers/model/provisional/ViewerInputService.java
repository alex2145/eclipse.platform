/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.viewers.model.provisional;

import org.eclipse.debug.internal.ui.viewers.model.ViewerAdapterService;
import org.eclipse.debug.internal.ui.viewers.model.ViewerInputUpdate;

/**
 * Service to compute a viewer input from a source object
 * for a given presentation context.
 * <p>
 * This class may be instantiated. Not intended to be subclassed.
 * </p>
 * @since 3.4
 */
public class ViewerInputService {
	
	// previous update request, cancelled when a new request comes in
	private IViewerInputUpdate fPendingUpdate = null;
	
	private IViewerInputRequestor fRequestor = null;
	private IPresentationContext fContext = null;
	
	private IViewerInputRequestor fProxyRequest = new IViewerInputRequestor() {
		public void viewerInputComplete(final IViewerInputUpdate update) {
			synchronized (ViewerInputService.this) {
				fPendingUpdate = null;
			}
			fRequestor.viewerInputComplete(update);
		}
	};
	
	/**
	 * Constructs a viewer input service for the given requester and presentation context.
	 * 
	 * @param requestor client requesting viewer inputs 
	 * @param context context for which inputs are required
	 */
	public ViewerInputService(IViewerInputRequestor requestor, IPresentationContext context) {
		fRequestor = requestor;
		fContext = context;
	}
	
	/**
	 * Resolves a viewer input derived from the given source object.
	 * Reports the result to the given this service's requester. A requester may be called back
	 * in the same or thread, or asynchronously in a different thread. Cancels any previous
	 * incomplete request from this service's requester.
	 * 
	 * @param source source from which to derive a viewer input
	 */
	public void resolveViewerInput(Object source) {
		IViewerInputProvider provdier = ViewerAdapterService.getInputProvider(source);
		synchronized (this) {
			// cancel any pending update
			if (fPendingUpdate != null) {
				fPendingUpdate.cancel();
			}
			fPendingUpdate = new ViewerInputUpdate(fContext, fProxyRequest, source);
		}
		if (provdier == null) {
			fPendingUpdate.setViewerInput(source);
			fRequestor.viewerInputComplete(fPendingUpdate);
		} else {
			provdier.update(fPendingUpdate);
		}
	}
	
}
