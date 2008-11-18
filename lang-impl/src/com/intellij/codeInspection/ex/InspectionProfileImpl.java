package com.intellij.codeInspection.ex;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.InspectionProfileConvertor;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.codeInspection.ModifiableModel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ExternalInfo;
import com.intellij.openapi.options.ExternalizableScheme;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.profile.ProfileEx;
import com.intellij.profile.ProfileManager;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.SeverityProvider;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author max
 */
public class InspectionProfileImpl extends ProfileEx implements ModifiableModel, InspectionProfile, ExternalizableScheme {
  @NonNls private static InspectionProfileImpl DEFAULT_PROFILE;

  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ex.InspectionProfileImpl");
  @NonNls private static final String VALID_VERSION = "1.0";

  private HashMap<String, InspectionTool> myTools = new HashMap<String, InspectionTool>();

  //diff map with base profile
  private LinkedHashMap<HighlightDisplayKey, ToolState> myDisplayLevelMap = new LinkedHashMap<HighlightDisplayKey, ToolState>();
  private boolean myLockedProfile = false;

  protected InspectionProfileImpl mySource;
  private InspectionProfileImpl myBaseProfile = null;
  @NonNls private static final String VERSION_TAG = "version";
  @NonNls private static final String INSPECTION_TOOL_TAG = "inspection_tool";
  @NonNls private static final String ENABLED_TAG = "enabled";
  @NonNls private static final String LEVEL_TAG = "level";
  @NonNls private static final String CLASS_TAG = "class";
  @NonNls private static final String PROFILE_NAME_TAG = "profile_name";
  @NonNls private static final String ROOT_ELEMENT_TAG = "inspections";
  private String myEnabledTool = null;
  @NonNls private static final String USED_LEVELS = "used_levels";

  private final InspectionToolRegistrar myRegistrar;
  @NonNls private static final String IS_LOCKED = "is_locked";
  private ExternalInfo myExternalInfo = new ExternalInfo();

//private String myBaseProfileName;

  public void setModified(final boolean modified) {
    myModified = modified;
  }

  private boolean myModified = false;
  private final AtomicBoolean myInitialized = new AtomicBoolean(false);

  private VisibleTreeState myVisibleTreeState = new VisibleTreeState();

  public InspectionProfileImpl(InspectionProfileImpl inspectionProfile) {
    super(inspectionProfile.getName());

    myRegistrar = inspectionProfile.myRegistrar;
    myTools = new HashMap<String, InspectionTool>();
    initInspectionTools();

    myDisplayLevelMap = new LinkedHashMap<HighlightDisplayKey, ToolState>(inspectionProfile.myDisplayLevelMap);
    myVisibleTreeState = new VisibleTreeState(inspectionProfile.myVisibleTreeState);

    myBaseProfile = inspectionProfile.myBaseProfile;
    myLocal = inspectionProfile.myLocal;
    myLockedProfile = inspectionProfile.myLockedProfile;
    mySource = inspectionProfile;
    setProfileManager(inspectionProfile.getProfileManager());
    copyFrom(inspectionProfile);
  }

  public InspectionProfileImpl(final String inspectionProfile, final InspectionToolRegistrar registrar, final ProfileManager profileManager) {
    super(inspectionProfile);
    myRegistrar = registrar;
    myBaseProfile = getDefaultProfile();
    setProfileManager(profileManager);
  }

  public InspectionProfileImpl(@NonNls String name) {
    super(name);
    myRegistrar = InspectionToolRegistrar.getInstance();
    setProfileManager(InspectionProfileManager.getInstance());
  }

  public InspectionProfile getParentProfile() {
    return mySource;
  }

  public String getBaseProfileName() {
    if (myBaseProfile == null) return null;
    return myBaseProfile.getName();
  }

  public void setBaseProfile(InspectionProfile profile) {
    myBaseProfile = (InspectionProfileImpl)profile;
  }

  @SuppressWarnings({"SimplifiableIfStatement"})
  public boolean isChanged() {
    if (mySource != null && mySource.myLockedProfile != myLockedProfile) return true;
    return myModified;
  }

  public VisibleTreeState getExpandedNodes() {
    return myVisibleTreeState;
  }

  private static boolean toolSettingsAreEqual(HighlightDisplayKey key,
                                       InspectionProfileImpl profile1,
                                       InspectionProfileImpl profile2) {
    final String toolName = key.toString();
    final InspectionProfileEntry tool1 = profile1.getInspectionTool(toolName);//findInspectionToolByName(profile1, toolDisplayName);
    final InspectionProfileEntry tool2 = profile2.getInspectionTool(toolName);//findInspectionToolByName(profile2, toolDisplayName);
    if (tool1 == null && tool2 == null) {
      return true;
    }
    if (tool1 != null && tool2 != null) {
      try {
        @NonNls String tempRoot = "root";
        Element oldToolSettings = new Element(tempRoot);
        tool1.writeSettings(oldToolSettings);
        Element newToolSettings = new Element(tempRoot);
        tool2.writeSettings(newToolSettings);
        return JDOMUtil.areElementsEqual(oldToolSettings, newToolSettings);
      }
      catch (WriteExternalException e) {
        LOG.error(e);
      }
    }
    return false;
  }

  public boolean isProperSetting(HighlightDisplayKey key) {
    if (myBaseProfile == null) {
      return false;
    }
    final boolean toolsSettings = toolSettingsAreEqual(key, this, myBaseProfile);
    if (myDisplayLevelMap.keySet().contains(key)) {
      if (toolsSettings && myDisplayLevelMap.get(key).equals(myBaseProfile.getToolState(key))) {
        if (!myLockedProfile) {
          myDisplayLevelMap.remove(key);
        }
        return false;
      }
      return true;
    }


    if (!toolsSettings) {
      myDisplayLevelMap.put(key, myBaseProfile.getToolState(key));
      return true;
    }

    return false;
  }


  public void resetToBase() {
    myDisplayLevelMap.clear();
    copyToolsConfigurations(myBaseProfile);
    if (myLockedProfile && myBaseProfile != null) { //store whole state for locked profiles
      for (InspectionProfileEntry entry : myBaseProfile.getInspectionTools()) {
        final HighlightDisplayKey displayKey = HighlightDisplayKey.find(entry.getShortName());
        myDisplayLevelMap.put(displayKey, myBaseProfile.getToolState(displayKey));
      }
    }
  }

  public void resetToEmpty() {
    final InspectionProfileEntry[] profileEntries = getInspectionTools();
    for (InspectionProfileEntry entry : profileEntries) {
      disableTool(entry.getShortName());
    }
  }

  public String getName() {
    return myName;
  }

  public void patchTool(InspectionProfileEntry tool) {
    myTools.put(tool.getShortName(), (InspectionTool)tool);
  }

  public HighlightDisplayLevel getErrorLevel(HighlightDisplayKey inspectionToolKey) {
    HighlightDisplayLevel level = getToolState(inspectionToolKey).getLevel();
    if (!((SeverityProvider)getProfileManager()).getOwnSeverityRegistrar().isSeverityValid(level.getSeverity())){
      level = HighlightDisplayLevel.WARNING;
      setErrorLevel(inspectionToolKey, level);
    }
    return level;
  }

  private ToolState getToolState(HighlightDisplayKey key) {
    ToolState state = myDisplayLevelMap.get(key);
    if (state == null) {
      if (myBaseProfile != null) {
        state = myBaseProfile.getToolState(key);
        if (myLockedProfile && state != null){
          state.myEnabled = false;
        }
      }
    }
    //default level for converted profiles
    if (state == null) {
      state = new ToolState(HighlightDisplayLevel.WARNING, false);
    }
    return state;
  }

  public void readExternal(Element element) throws InvalidDataException {
    super.readExternal(element);
    initInspectionTools();
    myDisplayLevelMap.clear();
    final String version = element.getAttributeValue(VERSION_TAG);
    if (version == null || !version.equals(VALID_VERSION)) {
      try {
        element = InspectionProfileConvertor.convertToNewFormat(element, this);
      }
      catch (IOException e) {
        LOG.error(e);
      }
      catch (JDOMException e) {
        LOG.error(e);
      }
    }

    final String locked = element.getAttributeValue(IS_LOCKED);
    if (locked != null) {
      myLockedProfile = Boolean.parseBoolean(locked);
    }

    final Element highlightElement = element.getChild(USED_LEVELS);
    if (highlightElement != null) { //from old profiles
      ((SeverityProvider)getProfileManager()).getOwnSeverityRegistrar().readExternal(highlightElement);
    }

    myBaseProfile = getDefaultProfile();

    for (final Object o : element.getChildren(INSPECTION_TOOL_TAG)) {
      Element toolElement = (Element)o;

      String toolClassName = toolElement.getAttributeValue(CLASS_TAG);

      final String levelName = toolElement.getAttributeValue(LEVEL_TAG);
      HighlightDisplayLevel level = HighlightDisplayLevel.find(((SeverityProvider)getProfileManager()).getOwnSeverityRegistrar().getSeverity(levelName));
      if (level == null || level == HighlightDisplayLevel.DO_NOT_SHOW) {//from old profiles
        level = HighlightDisplayLevel.WARNING;
      }

      InspectionTool tool = myTools.get(toolClassName);
      if (tool != null) {
        tool.readSettings(toolElement);
      }

      HighlightDisplayKey key = HighlightDisplayKey.find(toolClassName);
      if (key == null) continue; //tool was somehow dropped

      final String enabled = toolElement.getAttributeValue(ENABLED_TAG);
      myDisplayLevelMap.put(key, new ToolState(level, enabled != null && Boolean.parseBoolean(enabled)));
    }
  }


  public void writeExternal(Element element) throws WriteExternalException {
    super.writeExternal(element);
    element.setAttribute(VERSION_TAG, VALID_VERSION);
    element.setAttribute(IS_LOCKED, String.valueOf(myLockedProfile));

    for (final HighlightDisplayKey key : myDisplayLevelMap.keySet()) {
      Element inspectionElement = new Element(INSPECTION_TOOL_TAG);
      final String toolName = key.toString();
      inspectionElement.setAttribute(CLASS_TAG, toolName);
      inspectionElement.setAttribute(LEVEL_TAG, getToolState(key).getLevel().toString());
      inspectionElement.setAttribute(ENABLED_TAG, Boolean.toString(isToolEnabled(key)));

      final InspectionTool tool = myTools.get(toolName);
      if (tool != null) {
        tool.writeSettings(inspectionElement);
      }
      element.addContent(inspectionElement);
    }
  }

  public InspectionProfileEntry getInspectionTool(String shortName) {
    initInspectionTools();
    return myTools.get(shortName);
  }
  
  public InspectionProfileEntry getToolById(String id) {
    initInspectionTools();
    for (InspectionTool tool : myTools.values()) {
      String toolId = tool instanceof LocalInspectionToolWrapper ? ((LocalInspectionToolWrapper)tool).getTool().getID() : tool.getShortName();
      if (id.equals(toolId)) return tool;
    }
    return null;
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public void save() throws IOException {
    /*
    if (isLocal()) {
      if (myName.compareTo("Default") == 0 && myElement == null){
        myElement = new Element(ROOT_ELEMENT_TAG);
      }
      if (myElement != null) {
        try {
          myElement = new Element(ROOT_ELEMENT_TAG);
          myElement.setAttribute(PROFILE_NAME_TAG, myName);
          writeExternal(myElement);
          myVisibleTreeState.writeExternal(myElement);
        }
        catch (WriteExternalException e) {
          LOG.error(e);
        }
      }
    }
    */
    InspectionProfileManager.getInstance().fireProfileChanged(this);
  }

  public boolean isEditable() {
    return myEnabledTool == null;
  }

  public String getDisplayName() {
    return isEditable() ? getName() : myEnabledTool;
  }

  public void setEditable(final String displayName) {
    myEnabledTool = displayName;
  }

  public void load(Element element) {
    try {
      readExternal(element);
      myVisibleTreeState.readExternal(element);
    }
    catch (Exception e) {
      ApplicationManager.getApplication().invokeLater(new Runnable(){
        public void run() {
          Messages.showErrorDialog(InspectionsBundle.message("inspection.error.loading.message", 0,  getName()), InspectionsBundle.message("inspection.errors.occured.dialog.title"));
        }
      }, ModalityState.NON_MODAL);
    }
  }

  public boolean isDefault() {
    return myDisplayLevelMap.isEmpty();
  }

  public boolean isProfileLocked() {
    return myLockedProfile;
  }

  public void lockProfile(boolean isLocked){
    for (InspectionTool tool : myTools.values()) {
      final HighlightDisplayKey key = HighlightDisplayKey.find(tool.getShortName());
      if (isLocked){
        myDisplayLevelMap.put(key, getToolState(key));
      } else if (!isProperSetting(key)){
        myDisplayLevelMap.remove(key);
      }
    }
    myLockedProfile = isLocked;
  }

  public InspectionProfileEntry[] getInspectionTools() {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
     initInspectionTools();
    }
    ArrayList<InspectionTool> result = new ArrayList<InspectionTool>();
    result.addAll(myTools.values());
    return result.toArray(new InspectionTool[result.size()]);
  }

  public boolean wasInitialized() {
    return myInitialized.get();
  }

  public void initInspectionTools() {
    if (!myInitialized.getAndSet(true)) {
      if (myBaseProfile != null){
        myBaseProfile.initInspectionTools();
      }
      final InspectionTool[] tools = myRegistrar.createTools();
      for (InspectionTool tool : tools) {
        final String shortName = tool.getShortName();
        if (HighlightDisplayKey.find(shortName) == null) {
          if (tool instanceof LocalInspectionToolWrapper) {
            HighlightDisplayKey.register(shortName, tool.getDisplayName(), ((LocalInspectionToolWrapper)tool).getTool().getID());
          } else {
            HighlightDisplayKey.register(shortName);
          }
        }        
        myTools.put(tool.getShortName(), tool);
      }
      if (mySource != null){
        copyToolsConfigurations(mySource);
      }
      //load();
    }
  }

  public ModifiableModel getModifiableModel() {
    InspectionProfileImpl modifiableModel = new InspectionProfileImpl(this);
    modifiableModel.myExternalInfo.copy(myExternalInfo);
    return modifiableModel;
  }

  public void copyFrom(InspectionProfile profile) {
    final InspectionProfileImpl inspectionProfile = (InspectionProfileImpl)profile;
    super.copyFrom(inspectionProfile);
    if (profile == null) return;
    myDisplayLevelMap = new LinkedHashMap<HighlightDisplayKey, ToolState>(inspectionProfile.myDisplayLevelMap);
    myBaseProfile = inspectionProfile.myBaseProfile;
    copyToolsConfigurations(inspectionProfile);
  }

  public void inheritFrom(InspectionProfile profile) {
    final InspectionProfileImpl inspectionProfile = (InspectionProfileImpl)profile;
    myBaseProfile = inspectionProfile;
    copyToolsConfigurations(inspectionProfile);
  }

  private void copyToolsConfigurations(InspectionProfileImpl profile) {
    try {
      final InspectionProfileEntry[] inspectionTools = getInspectionTools();
      for (InspectionProfileEntry inspectionTool : inspectionTools) {
        copyToolConfig(inspectionTool, profile);
      }
    }
    catch (WriteExternalException e) {
      LOG.error(e);
    }
    catch (InvalidDataException e) {
      LOG.error(e);
    }
  }

  private static void copyToolConfig(final InspectionProfileEntry inspectionTool, final InspectionProfileImpl profile)
    throws WriteExternalException, InvalidDataException {
    final String name = inspectionTool.getShortName();
    final InspectionProfileEntry tool = profile.getInspectionTool(name);
    if (tool != null){
      @NonNls String tempRoot = "config";
      Element config = new Element(tempRoot);
      tool.writeSettings(config);
      inspectionTool.readSettings(config);
    }
  }

  public void cleanup() {
    if (myTools.isEmpty()) return;
    if (!myTools.isEmpty()) {
      for (final String key : myTools.keySet()) {
        final InspectionTool tool = myTools.get(key);
        if (tool.getContext() != null) {
          tool.cleanup();
        }
      }
    }
  }

  public void enableTool(String inspectionTool){
    final HighlightDisplayKey key = HighlightDisplayKey.find(inspectionTool);
    setState(key,
             new ToolState(getErrorLevel(key), true));
  }

  public void disableTool(String inspectionTool){
    final HighlightDisplayKey key = HighlightDisplayKey.find(inspectionTool);
    setState(key,
             new ToolState(getErrorLevel(key), false));
  }


  public void setErrorLevel(HighlightDisplayKey key, HighlightDisplayLevel level) {
    setState(key, new ToolState(level, isToolEnabled(key)));
  }

  private void setState(HighlightDisplayKey key, ToolState state) {
    if (myBaseProfile != null &&
        state.equals(myBaseProfile.getToolState(key))) {
      if (toolSettingsAreEqual(key, this, myBaseProfile) && !myLockedProfile){ //settings may differ
        myDisplayLevelMap.remove(key);
      } else {
        myDisplayLevelMap.put(key, state);
      }
    }
    else {
      myDisplayLevelMap.put(key, state);
    }
  }

  public boolean isToolEnabled(HighlightDisplayKey key) {
    final ToolState toolState = getToolState(key);
    return toolState != null && toolState.isEnabled();    
  }

  public boolean isExecutable() {
    initInspectionTools();
    for (String name : myTools.keySet()) {
      if (isToolEnabled(HighlightDisplayKey.find(name))){
        return true;
      }
    }
    return false;
  }

  //invoke when isChanged() == true
  public void commit() throws IOException {
    LOG.assertTrue(mySource != null);
    mySource.commit(this);
    getProfileManager().updateProfile(mySource);
    mySource = null;
  }

  private void commit(InspectionProfileImpl inspectionProfile) throws IOException {
    myName = inspectionProfile.myName;
    myLocal = inspectionProfile.myLocal;
    myLockedProfile = inspectionProfile.myLockedProfile;
    myDisplayLevelMap = inspectionProfile.myDisplayLevelMap;
    myVisibleTreeState = inspectionProfile.myVisibleTreeState;
    myBaseProfile = inspectionProfile.myBaseProfile;
    myTools = inspectionProfile.myTools;

    myExternalInfo.copy(inspectionProfile.getExternalInfo());

    InspectionProfileManager.getInstance().fireProfileChanged(inspectionProfile);
  }

  public static synchronized InspectionProfileImpl getDefaultProfile() {
    if (DEFAULT_PROFILE == null) {
      DEFAULT_PROFILE = new InspectionProfileImpl("Default");
      final InspectionProfileEntry[] inspectionTools = DEFAULT_PROFILE.getInspectionTools();
      for (InspectionProfileEntry tool : inspectionTools) {
        final String shortName = tool.getShortName();
        /*
        final String shortName = tool.getShortName();
        if (tool instanceof LocalInspectionToolWrapper) {
          HighlightDisplayKey.register(shortName, tool.getDisplayName(), ((LocalInspectionToolWrapper)tool).getTool().getID());
        } else {
          HighlightDisplayKey.register(shortName);
        } */
        DEFAULT_PROFILE.myDisplayLevelMap.put(HighlightDisplayKey.find(shortName), new ToolState(tool.getDefaultLevel(), tool.isEnabledByDefault()));
      }
    }
    return DEFAULT_PROFILE;
  }

  public Document saveToDocument() throws WriteExternalException {
    if (isLocal()) {
      Element root = new Element(ROOT_ELEMENT_TAG);
      root.setAttribute(PROFILE_NAME_TAG, myName);
      writeExternal(root);
      //myVisibleTreeState.writeExternal(root);
      return new Document(root);
    }
    else {
      return null;
    }

  }

  public VisibleTreeState getVisibleTreeState() {
    return myVisibleTreeState;
  }

  public void setVisibleTreeState(final VisibleTreeState state) {
    myVisibleTreeState = state;
  }

  private static class ToolState {
    private final HighlightDisplayLevel myLevel;
    private boolean myEnabled;

    public ToolState(final HighlightDisplayLevel level, final boolean enabled) {
      myLevel = level;
      myEnabled = enabled;
    }

    public HighlightDisplayLevel getLevel() {
      return myLevel;
    }

    public boolean isEnabled() {
      return myEnabled;
    }

    public boolean equals(Object object) {
      if (!(object instanceof ToolState)) return false;
      final ToolState state = (ToolState)object;
      return myLevel == state.getLevel() &&
             myEnabled == state.isEnabled();
    }

    public int hashCode() {
      return myLevel.hashCode();
    }
  }

  @NotNull
  public ExternalInfo getExternalInfo() {
    return myExternalInfo;
  }
}
