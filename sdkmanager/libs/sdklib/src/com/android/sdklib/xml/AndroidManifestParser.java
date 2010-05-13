/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sdklib.xml;

import com.android.sdklib.SdkConstants;
import com.android.sdklib.io.IAbstractFile;
import com.android.sdklib.io.IAbstractFolder;
import com.android.sdklib.io.StreamException;
import com.sun.rowset.internal.XmlErrorHandler;

import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class AndroidManifestParser {

    private final static int LEVEL_MANIFEST = 0;
    private final static int LEVEL_APPLICATION = 1;
    private final static int LEVEL_ACTIVITY = 2;
    private final static int LEVEL_INTENT_FILTER = 3;
    private final static int LEVEL_CATEGORY = 4;

    private final static String ACTION_MAIN = "android.intent.action.MAIN"; //$NON-NLS-1$
    private final static String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"; //$NON-NLS-1$

    /**
     * Class containing the manifest info obtained during the parsing.
     */
    public static class ManifestData {

        /** Application package */
        private String mPackage;
        /** List of all activities */
        private final ArrayList<Activity> mActivities = new ArrayList<Activity>();
        /** Launcher activity */
        private Activity mLauncherActivity = null;
        /** list of process names declared by the manifest */
        private Set<String> mProcesses = null;
        /** debuggable attribute value. If null, the attribute is not present. */
        private Boolean mDebuggable = null;
        /** API level requirement. if null the attribute was not present. */
        private String mApiLevelRequirement = null;
        /** List of all instrumentations declared by the manifest */
        private final ArrayList<Instrumentation> mInstrumentations =
            new ArrayList<Instrumentation>();
        /** List of all libraries in use declared by the manifest */
        private final ArrayList<String> mLibraries = new ArrayList<String>();

        /**
         * Returns the package defined in the manifest, if found.
         * @return The package name or null if not found.
         */
        public String getPackage() {
            return mPackage;
        }

        /**
         * Returns the list of activities found in the manifest.
         * @return An array of fully qualified class names, or empty if no activity were found.
         */
        public Activity[] getActivities() {
            return mActivities.toArray(new Activity[mActivities.size()]);
        }

        /**
         * Returns the name of one activity found in the manifest, that is configured to show
         * up in the HOME screen.
         * @return the fully qualified name of a HOME activity or null if none were found.
         */
        public Activity getLauncherActivity() {
            return mLauncherActivity;
        }

        /**
         * Returns the list of process names declared by the manifest.
         */
        public String[] getProcesses() {
            if (mProcesses != null) {
                return mProcesses.toArray(new String[mProcesses.size()]);
            }

            return new String[0];
        }

        /**
         * Returns the <code>debuggable</code> attribute value or null if it is not set.
         */
        public Boolean getDebuggable() {
            return mDebuggable;
        }

        /**
         * Returns the <code>minSdkVersion</code> attribute, or null if it's not set.
         */
        public String getApiLevelRequirement() {
            return mApiLevelRequirement;
        }

        /**
         * Returns the list of instrumentations found in the manifest.
         * @return An array of {@link Instrumentation}, or empty if no instrumentations were
         * found.
         */
        public Instrumentation[] getInstrumentations() {
            return mInstrumentations.toArray(new Instrumentation[mInstrumentations.size()]);
        }

        /**
         * Returns the list of libraries in use found in the manifest.
         * @return An array of library names, or empty if no libraries were found.
         */
        public String[] getUsesLibraries() {
            return mLibraries.toArray(new String[mLibraries.size()]);
        }

        private void addProcessName(String processName) {
            if (mProcesses == null) {
                mProcesses = new TreeSet<String>();
            }

            mProcesses.add(processName);
        }

    }

    /**
     * Instrumentation info obtained from manifest
     */
    public static class Instrumentation {
        private final String mName;
        private final String mTargetPackage;

        Instrumentation(String name, String targetPackage) {
            mName = name;
            mTargetPackage = targetPackage;
        }

        /**
         * Returns the fully qualified instrumentation class name
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the Android app package that is the target of this instrumentation
         */
        public String getTargetPackage() {
            return mTargetPackage;
        }
    }

    /**
     * Activity info obtained from the manifest.
     */
    public static class Activity {
        private final String mName;
        private final boolean mIsExported;
        private boolean mHasAction = false;
        private boolean mHasMainAction = false;
        private boolean mHasLauncherCategory = false;

        public Activity(String name, boolean exported) {
            mName = name;
            mIsExported = exported;
        }

        public String getName() {
            return mName;
        }

        public boolean isExported() {
            return mIsExported;
        }

        public boolean hasAction() {
            return mHasAction;
        }

        public boolean isHomeActivity() {
            return mHasMainAction && mHasLauncherCategory;
        }

        void setHasAction(boolean hasAction) {
            mHasAction = hasAction;
        }

        /** If the activity doesn't yet have a filter set for the launcher, this resets both
         * flags. This is to handle multiple intent-filters where one could have the valid
         * action, and another one of the valid category.
         */
        void resetIntentFilter() {
            if (isHomeActivity() == false) {
                mHasMainAction = mHasLauncherCategory = false;
            }
        }

        void setHasMainAction(boolean hasMainAction) {
            mHasMainAction = hasMainAction;
        }

        void setHasLauncherCategory(boolean hasLauncherCategory) {
            mHasLauncherCategory = hasLauncherCategory;
        }
    }

    public interface ManifestErrorHandler extends ErrorHandler {
        /**
         * Handles a parsing error and an optional line number.
         * @param exception
         * @param lineNumber
         */
        void handleError(Exception exception, int lineNumber);

        /**
         * Checks that a class is valid and can be used in the Android Manifest.
         * <p/>
         * Errors are put as {@link IMarker} on the manifest file.
         * @param locator
         * @param className the fully qualified name of the class to test.
         * @param superClassName the fully qualified name of the class it is supposed to extend.
         * @param testVisibility if <code>true</code>, the method will check the visibility of
         * the class or of its constructors.
         */
        void checkClass(Locator locator, String className, String superClassName,
                boolean testVisibility);
    }

    /**
     * XML error & data handler used when parsing the AndroidManifest.xml file.
     * <p/>
     * This serves both as an {@link XmlErrorHandler} to report errors and as a data repository
     * to collect data from the manifest.
     */
    private static class ManifestHandler extends DefaultHandler {

        //--- temporary data/flags used during parsing
        private final ManifestData mManifestData;
        private final ManifestErrorHandler mErrorHandler;
        private int mCurrentLevel = 0;
        private int mValidLevel = 0;
        private Activity mCurrentActivity = null;
        private Locator mLocator;

        /**
         * Creates a new {@link ManifestHandler}, which is also an {@link XmlErrorHandler}.
         *
         * @param manifestFile The manifest file being parsed. Can be null.
         * @param errorListener An optional error listener.
         * @param gatherData True if data should be gathered.
         * @param javaProject The java project holding the manifest file. Can be null.
         * @param markErrors True if errors should be marked as Eclipse Markers on the resource.
         */
        ManifestHandler(IAbstractFile manifestFile, ManifestData manifestData,
                ManifestErrorHandler errorHandler) {
            super();
            mManifestData = manifestData;
            mErrorHandler = errorHandler;
        }


        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#setDocumentLocator(org.xml.sax.Locator)
         */
        @Override
        public void setDocumentLocator(Locator locator) {
            mLocator = locator;
            super.setDocumentLocator(locator);
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String,
         * java.lang.String, org.xml.sax.Attributes)
         */
        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            try {
                if (mManifestData == null) {
                    return;
                }

                // if we're at a valid level
                if (mValidLevel == mCurrentLevel) {
                    String value;
                    switch (mValidLevel) {
                        case LEVEL_MANIFEST:
                            if (AndroidManifest.NODE_MANIFEST.equals(localName)) {
                                // lets get the package name.
                                mManifestData.mPackage = getAttributeValue(attributes,
                                        AndroidManifest.ATTRIBUTE_PACKAGE,
                                        false /* hasNamespace */);
                                mValidLevel++;
                            }
                            break;
                        case LEVEL_APPLICATION:
                            if (AndroidManifest.NODE_APPLICATION.equals(localName)) {
                                value = getAttributeValue(attributes,
                                        AndroidManifest.ATTRIBUTE_PROCESS,
                                        true /* hasNamespace */);
                                if (value != null) {
                                    mManifestData.addProcessName(value);
                                }

                                value = getAttributeValue(attributes,
                                        AndroidManifest.ATTRIBUTE_DEBUGGABLE,
                                        true /* hasNamespace*/);
                                if (value != null) {
                                    mManifestData.mDebuggable = Boolean.parseBoolean(value);
                                }

                                mValidLevel++;
                            } else if (AndroidManifest.NODE_USES_SDK.equals(localName)) {
                                mManifestData.mApiLevelRequirement = getAttributeValue(attributes,
                                        AndroidManifest.ATTRIBUTE_MIN_SDK_VERSION,
                                        true /* hasNamespace */);
                            } else if (AndroidManifest.NODE_INSTRUMENTATION.equals(localName)) {
                                processInstrumentationNode(attributes);
                            }
                            break;
                        case LEVEL_ACTIVITY:
                            if (AndroidManifest.NODE_ACTIVITY.equals(localName)) {
                                processActivityNode(attributes);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_SERVICE.equals(localName)) {
                                processNode(attributes, SdkConstants.CLASS_SERVICE);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_RECEIVER.equals(localName)) {
                                processNode(attributes, SdkConstants.CLASS_BROADCASTRECEIVER);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_PROVIDER.equals(localName)) {
                                processNode(attributes, SdkConstants.CLASS_CONTENTPROVIDER);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_USES_LIBRARY.equals(localName)) {
                                value = getAttributeValue(attributes,
                                        AndroidManifest.ATTRIBUTE_NAME,
                                        true /* hasNamespace */);
                                if (value != null) {
                                    mManifestData.mLibraries.add(value);
                                }
                            }
                            break;
                        case LEVEL_INTENT_FILTER:
                            // only process this level if we are in an activity
                            if (mCurrentActivity != null &&
                                    AndroidManifest.NODE_INTENT.equals(localName)) {
                                mCurrentActivity.resetIntentFilter();
                                mValidLevel++;
                            }
                            break;
                        case LEVEL_CATEGORY:
                            if (mCurrentActivity != null) {
                                if (AndroidManifest.NODE_ACTION.equals(localName)) {
                                    // get the name attribute
                                    String action = getAttributeValue(attributes,
                                            AndroidManifest.ATTRIBUTE_NAME,
                                            true /* hasNamespace */);
                                    if (action != null) {
                                        mCurrentActivity.setHasAction(true);
                                        mCurrentActivity.setHasMainAction(
                                                ACTION_MAIN.equals(action));
                                    }
                                } else if (AndroidManifest.NODE_CATEGORY.equals(localName)) {
                                    String category = getAttributeValue(attributes,
                                            AndroidManifest.ATTRIBUTE_NAME,
                                            true /* hasNamespace */);
                                    if (CATEGORY_LAUNCHER.equals(category)) {
                                        mCurrentActivity.setHasLauncherCategory(true);
                                    }
                                }

                                // no need to increase mValidLevel as we don't process anything
                                // below this level.
                            }
                            break;
                    }
                }

                mCurrentLevel++;
            } finally {
                super.startElement(uri, localName, name, attributes);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String,
         * java.lang.String)
         */
        @Override
        public void endElement(String uri, String localName, String name) throws SAXException {
            try {
                if (mManifestData == null) {
                    return;
                }

                // decrement the levels.
                if (mValidLevel == mCurrentLevel) {
                    mValidLevel--;
                }
                mCurrentLevel--;

                // if we're at a valid level
                // process the end of the element
                if (mValidLevel == mCurrentLevel) {
                    switch (mValidLevel) {
                        case LEVEL_ACTIVITY:
                            mCurrentActivity = null;
                            break;
                        case LEVEL_INTENT_FILTER:
                            // if we found both a main action and a launcher category, this is our
                            // launcher activity!
                            if (mManifestData.mLauncherActivity == null &&
                                    mCurrentActivity != null &&
                                    mCurrentActivity.isHomeActivity() &&
                                    mCurrentActivity.isExported()) {
                                mManifestData.mLauncherActivity = mCurrentActivity;
                            }
                            break;
                        default:
                            break;
                    }

                }
            } finally {
                super.endElement(uri, localName, name);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#error(org.xml.sax.SAXParseException)
         */
        @Override
        public void error(SAXParseException e) {
            if (mErrorHandler != null) {
                mErrorHandler.handleError(e, e.getLineNumber());
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#fatalError(org.xml.sax.SAXParseException)
         */
        @Override
        public void fatalError(SAXParseException e) {
            if (mErrorHandler != null) {
                mErrorHandler.handleError(e, e.getLineNumber());
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#warning(org.xml.sax.SAXParseException)
         */
        @Override
        public void warning(SAXParseException e) throws SAXException {
            if (mErrorHandler != null) {
                mErrorHandler.warning(e);
            }
        }

        /**
         * Processes the activity node.
         * @param attributes the attributes for the activity node.
         */
        private void processActivityNode(Attributes attributes) {
            // lets get the activity name, and add it to the list
            String activityName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (activityName != null) {
                activityName = AndroidManifest.combinePackageAndClassName(mManifestData.mPackage,
                        activityName);

                // get the exported flag.
                String exportedStr = getAttributeValue(attributes,
                        AndroidManifest.ATTRIBUTE_EXPORTED, true);
                boolean exported = exportedStr == null ||
                        exportedStr.toLowerCase().equals("true"); // $NON-NLS-1$
                mCurrentActivity = new Activity(activityName, exported);
                mManifestData.mActivities.add(mCurrentActivity);

                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, activityName, SdkConstants.CLASS_ACTIVITY,
                            true /* testVisibility */);
                }
            } else {
                // no activity found! Aapt will output an error,
                // so we don't have to do anything
                mCurrentActivity = null;
            }

            String processName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_PROCESS,
                    true /* hasNamespace */);
            if (processName != null) {
                mManifestData.addProcessName(processName);
            }
        }

        /**
         * Processes the service/receiver/provider nodes.
         * @param attributes the attributes for the activity node.
         * @param superClassName the fully qualified name of the super class that this
         * node is representing
         */
        private void processNode(Attributes attributes, String superClassName) {
            // lets get the class name, and check it if required.
            String serviceName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (serviceName != null) {
                serviceName = AndroidManifest.combinePackageAndClassName(mManifestData.mPackage,
                        serviceName);

                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, serviceName, superClassName,
                            false /* testVisibility */);
                }
            }

            String processName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_PROCESS,
                    true /* hasNamespace */);
            if (processName != null) {
                mManifestData.addProcessName(processName);
            }
        }

        /**
         * Processes the instrumentation nodes.
         * @param attributes the attributes for the activity node.
         * node is representing
         */
        private void processInstrumentationNode(Attributes attributes) {
            // lets get the class name, and check it if required.
            String instrumentationName = getAttributeValue(attributes,
                    AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (instrumentationName != null) {
                String instrClassName = AndroidManifest.combinePackageAndClassName(
                        mManifestData.mPackage, instrumentationName);
                String targetPackage = getAttributeValue(attributes,
                        AndroidManifest.ATTRIBUTE_TARGET_PACKAGE,
                        true /* hasNamespace */);
                mManifestData.mInstrumentations.add(
                        new Instrumentation(instrClassName, targetPackage));
                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, instrClassName,
                            SdkConstants.CLASS_INSTRUMENTATION, true /* testVisibility */);
                }
            }
        }

        /**
         * Searches through the attributes list for a particular one and returns its value.
         * @param attributes the attribute list to search through
         * @param attributeName the name of the attribute to look for.
         * @param hasNamespace Indicates whether the attribute has an android namespace.
         * @return a String with the value or null if the attribute was not found.
         * @see SdkConstants#NS_RESOURCES
         */
        private String getAttributeValue(Attributes attributes, String attributeName,
                boolean hasNamespace) {
            int count = attributes.getLength();
            for (int i = 0 ; i < count ; i++) {
                if (attributeName.equals(attributes.getLocalName(i)) &&
                        ((hasNamespace &&
                                SdkConstants.NS_RESOURCES.equals(attributes.getURI(i))) ||
                                (hasNamespace == false && attributes.getURI(i).length() == 0))) {
                    return attributes.getValue(i);
                }
            }

            return null;
        }

    }

    private final static SAXParserFactory sParserFactory;

    static {
        sParserFactory = SAXParserFactory.newInstance();
        sParserFactory.setNamespaceAware(true);
    }

    /**
     * Parses the Android Manifest, and returns a {@link ManifestData} object containing the
     * result of the parsing.
     *
     * @param manifestFile the {@link IAbstractFile} representing the manifest file.
     * @param gatherData indicates whether the parsing will extract data from the manifest. If false
     * the method will always return null.
     * @param errorHandler an optional errorHandler.
     * @return
     * @throws StreamException
     * @throws IOException
     * @throws SAXException
     * @throws ParserConfigurationException
     */
    public static ManifestData parse(
            IAbstractFile manifestFile,
            boolean gatherData,
            ManifestErrorHandler errorHandler)
            throws SAXException, IOException, StreamException, ParserConfigurationException {
        if (manifestFile != null) {
            SAXParser parser = sParserFactory.newSAXParser();

            ManifestData data = null;
            if (gatherData) {
                data = new ManifestData();
            }

            ManifestHandler manifestHandler = new ManifestHandler(manifestFile,
                    data, errorHandler);
            parser.parse(new InputSource(manifestFile.getContents()), manifestHandler);

            return data;
        }

        return null;
    }

    /**
     * Parses the Android Manifest, and returns an object containing the result of the parsing.
     *
     * <p/>
     * This is the equivalent of calling <pre>parse(manifestFile, true, null)</pre>
     *
     * @param manifestFile the manifest file to parse.
     * @throws ParserConfigurationException
     * @throws StreamException
     * @throws IOException
     * @throws SAXException
     */
    public static ManifestData parse(IAbstractFile manifestFile)
            throws SAXException, IOException, StreamException, ParserConfigurationException {
        return parse(manifestFile, true, null);
    }

    public static ManifestData parse(IAbstractFolder projectFolder)
            throws SAXException, IOException, StreamException, ParserConfigurationException {
        IAbstractFile manifestFile = getManifest(projectFolder);
        if (manifestFile == null) {
            throw new FileNotFoundException();
        }

        return parse(manifestFile, true, null);
    }

    /**
     * Returns an {@link IAbstractFile} object representing the manifest for the given project.
     *
     * @param project The project containing the manifest file.
     * @return An IAbstractFile object pointing to the manifest or null if the manifest
     *         is missing.
     */
    public static IAbstractFile getManifest(IAbstractFolder projectFolder) {
        IAbstractFile file = projectFolder.getFile(SdkConstants.FN_ANDROID_MANIFEST_XML);
        if (file.exists()) {
            return file;
        }

        return null;
    }
}