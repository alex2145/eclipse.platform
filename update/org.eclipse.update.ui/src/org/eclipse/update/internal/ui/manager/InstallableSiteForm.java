package org.eclipse.update.internal.ui.manager;
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
import org.eclipse.update.internal.ui.parts.*;
import org.eclipse.update.internal.ui.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.update.ui.forms.internal.*;
import org.eclipse.swt.layout.*;
import org.eclipse.ui.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.update.core.*;
import org.eclipse.update.internal.ui.model.*;
import org.eclipse.swt.custom.BusyIndicator;
import java.net.URL;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.events.*;
import org.eclipse.update.ui.forms.internal.engine.FormEngine;
public class InstallableSiteForm extends UpdateWebForm {
private static final String KEY_TITLE = "InstallableSitePage.title";
private static final String KEY_DESC = "InstallableSitePage.desc";
private static final String KEY_NEW_LOC = "InstallableSitePage.newLocation";

private IConfigurationSite currentSite;
private Label urlLabel;
	
public InstallableSiteForm(UpdateFormPage page) {
	super(page);
}

public void dispose() {
	super.dispose();
}

public void initialize(Object modelObject) {
	setHeadingText(UpdateUIPlugin.getResourceString(KEY_TITLE));
	setHeadingImage(UpdateUIPluginImages.get(UpdateUIPluginImages.IMG_FORM_BANNER));
	setHeadingUnderlineImage(UpdateUIPluginImages.get(UpdateUIPluginImages.IMG_FORM_UNDERLINE));
	super.initialize(modelObject);
	//((Composite)getControl()).layout(true);
}

protected void createContents(Composite parent) {
	HTMLTableLayout layout = new HTMLTableLayout();
	parent.setLayout(layout);
	layout.leftMargin = layout.rightMargin = 10;
	layout.topMargin = 10;
	layout.horizontalSpacing = 0;
	layout.verticalSpacing = 20;
	layout.numColumns = 1;

	FormWidgetFactory factory = getFactory();	
	urlLabel = factory.createHeadingLabel(parent, null);
	FormEngine desc = factory.createFormEngine(parent);
	desc.load(UpdateUIPlugin.getResourceString(KEY_DESC), true, true);
	TableData td = new TableData();
	td.align = TableData.FILL;
	td.grabHorizontal=true;
	desc.setLayoutData(td);
}

public void expandTo(Object obj) {
	if (obj instanceof IConfigurationSiteAdapter) {
		inputChanged(((IConfigurationSiteAdapter)obj).getConfigurationSite());
	}
}

private void inputChanged(IConfigurationSite csite) {
	ISite site = csite.getSite();
	urlLabel.setText(site.getURL().toString());
	urlLabel.getParent().layout();
	((Composite)getControl()).layout();
	getControl().redraw();
	currentSite = csite;
}
}