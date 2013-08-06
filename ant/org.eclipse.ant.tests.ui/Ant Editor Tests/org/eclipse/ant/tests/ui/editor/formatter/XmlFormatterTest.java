/*******************************************************************************
 * Copyright (c) 2004, 2013 John-Mason P. Shackelford and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     John-Mason P. Shackelford - initial API and implementation
 *     IBM Corporation - bug 84342
 *******************************************************************************/
package org.eclipse.ant.tests.ui.editor.formatter;

import junit.framework.TestCase;

import org.eclipse.ant.internal.ui.AntUIPlugin;
import org.eclipse.ant.internal.ui.editor.formatter.FormattingPreferences;
import org.eclipse.ant.internal.ui.editor.formatter.XmlFormatter;
import org.eclipse.ant.internal.ui.preferences.AntEditorPreferenceConstants;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

public class XmlFormatterTest extends TestCase {

    public final void testFormatUsingPreferenceStore() throws Exception{
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(AntUIPlugin.getUniqueIdentifier());
        if(node != null) {
        	node.putBoolean(AntEditorPreferenceConstants.FORMATTER_WRAP_LONG, true);
        	node.putInt(AntEditorPreferenceConstants.FORMATTER_MAX_LINE_LENGTH, 40);
        	node.putBoolean(AntEditorPreferenceConstants.FORMATTER_ALIGN, false);
        	node.putBoolean(AntEditorPreferenceConstants.FORMATTER_TAB_CHAR, true);
        	node.putInt(AntEditorPreferenceConstants.FORMATTER_TAB_SIZE, 4);
			node.flush();
        }
        String lineSep= System.getProperty("line.separator"); //$NON-NLS-1$
        String xmlDoc = "<project default=\"go\"><target name=\"go\" description=\"Demonstrate the wrapping of long tags.\"><echo>hi</echo></target></project>"; //$NON-NLS-1$
        String formattedDoc = XmlFormatter.format(xmlDoc);
        String expected = "<project default=\"go\">" + lineSep + "\t<target name=\"go\"" + lineSep + "\t        description=\"Demonstrate the wrapping of long tags.\">" + lineSep + "\t\t<echo>hi</echo>" + lineSep + "\t</target>" + lineSep + "</project>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(expected, formattedDoc);
    }

    public final void testFormatWithPreferenceParameter() {
        FormattingPreferences prefs = new FormattingPreferences() {
            public boolean wrapLongTags() { return true;}
            public int getMaximumLineWidth() { return 40;}
            public boolean alignElementCloseChar() { return false;}
            public boolean useSpacesInsteadOfTabs() { return true;}
            public int getTabWidth() { return 6;}
        };
        String lineSep= System.getProperty("line.separator"); //$NON-NLS-1$
        String xmlDoc = "<project default=\"go\"><target name=\"go\" description=\"Demonstrate the wrapping of long tags.\"><echo>hi</echo></target></project>"; //$NON-NLS-1$
        String formattedDoc = XmlFormatter.format(xmlDoc, prefs);
        String expected = "<project default=\"go\">" + lineSep + "      <target name=\"go\"" + lineSep + "              description=\"Demonstrate the wrapping of long tags.\">" + lineSep + "            <echo>hi</echo>" + lineSep + "      </target>" + lineSep + "</project>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(expected, formattedDoc);
    }
    
    /**
     * Bug 84342
     */
    public final void testFormatMaintainingLineSeparators() {
        FormattingPreferences prefs = new FormattingPreferences() {
            public boolean wrapLongTags() { return true;}
            public int getMaximumLineWidth() { return 40;}
            public boolean alignElementCloseChar() { return false;}
            public boolean useSpacesInsteadOfTabs() { return true;}
            public int getTabWidth() { return 6;}
        };
        String lineSep= System.getProperty("line.separator"); //$NON-NLS-1$
        String xmlDoc = "<project default=\"go\"><target name=\"go\" description=\"Demonstrate the wrapping of long tags.\"><echo>hi</echo></target>" + lineSep + lineSep + "</project>"; //$NON-NLS-1$ //$NON-NLS-2$
        String formattedDoc = XmlFormatter.format(xmlDoc, prefs);
        String expected = "<project default=\"go\">" + lineSep + "      <target name=\"go\"" + lineSep + "              description=\"Demonstrate the wrapping of long tags.\">" + lineSep + "            <echo>hi</echo>" + lineSep + "      </target>" + lineSep + lineSep + "</project>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(expected, formattedDoc);
    }

}