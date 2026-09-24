/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.totalseg;

import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.exp.CheckTreeModel;
import org.weasis.dicom.explorer.exp.ExportDicomView;
import org.weasis.dicom.op.CStore;
import org.weasis.dicom.param.AdvancedParams;
import org.weasis.dicom.param.ConnectOptions;
import org.weasis.dicom.param.DicomNode;
import org.weasis.dicom.param.DicomProgress;
import org.weasis.dicom.param.DicomState;

/**
 * Export page that sends the checked CT or MR series with a C-STORE to the TotalSegmentator store
 * SCP, which runs the segmentation.
 */
public class TotalSegView extends ExportDicomView {

  private static final Logger LOGGER = LoggerFactory.getLogger(TotalSegView.class);

  private static final String LAST_TASK = "lastTask";
  private static final String LAST_OUTPUT = "lastOutput";
  private static final String LAST_SEG_TYPE = "lastSegType";
  private static final String LAST_FAST = "lastFast";
  private static final String LAST_ROI_SUBSET = "lastRoiSubset";

  private final JComboBox<String> comboTask = new JComboBox<>();
  private final JComboBox<OutputType> comboOutput = new JComboBox<>(OutputType.values());
  private final JComboBox<SegType> comboSegType = new JComboBox<>(SegType.values());
  private final JLabel labelSegType = new JLabel(Messages.getString("seg.type") + StringUtil.COLON);
  private final JCheckBox checkFast = new JCheckBox(Messages.getString("fast.mode"));
  private final RoiCheckBoxList roiList = new RoiCheckBoxList();
  private final JLabel labelStatus = new JLabel();
  private final JButton buttonRefresh = new JButton(Messages.getString("refresh"));
  private final TaskCatalog catalog = new TaskCatalog();
  private final CheckTreeModel treeModel;
  private List<String> checkedRois = List.of();
  private JPanel fastPanel;

  // Kept aside so that moving through tasks without a fast mode does not clear the choice.
  private boolean fastRequested;

  public TotalSegView(DicomModel dicomModel, CheckTreeModel treeModel) {
    super(Messages.getString("TotalSegView.title"), 15, dicomModel, treeModel);
    this.treeModel = treeModel;
  }

  @Override
  public TotalSegView initGUI() {
    add(buildOptionsPanel());
    add(GuiUtils.boxVerticalStrut(ITEM_SEPARATOR));
    super.initGUI();
    add(GuiUtils.boxVerticalStrut(ITEM_SEPARATOR));
    add(GuiUtils.getFlowLayoutPanel(labelStatus));

    initTaskList();
    refreshTasksFromServer();
    return this;
  }

  private JPanel buildOptionsPanel() {
    // setSelected() does not fire it, so only a click updates the stored choice.
    checkFast.addActionListener(_ -> fastRequested = checkFast.isSelected());
    // A disabled component gets no mouse event, so its parent carries the tooltip explaining why.
    // Built before the combo listener, which updates it on the first selection.
    fastPanel = GuiUtils.getFlowLayoutPanel(0, 0, checkFast);

    JLabel labelTask = new JLabel(Messages.getString("task") + StringUtil.COLON);
    GuiUtils.setPreferredWidth(comboTask, 210, 185);
    comboTask.addActionListener(_ -> taskChanged());

    JLabel labelOutput = new JLabel(Messages.getString("output") + StringUtil.COLON);
    GuiUtils.setPreferredWidth(comboOutput, 200, 160);
    comboOutput.addActionListener(_ -> updateSegTypeState());

    GuiUtils.setPreferredWidth(comboSegType, 160, 120);
    comboSegType.setToolTipText(Messages.getString("seg.type.tooltip"));

    buttonRefresh.setToolTipText(Messages.getString("refresh.tooltip"));
    buttonRefresh.addActionListener(_ -> refreshTasksFromServer());

    JLabel labelRoi = new JLabel(Messages.getString("roi.subset") + StringUtil.COLON);
    roiList.setToolTipText(Messages.getString("roi.subset.tooltip"));
    JScrollPane roiScroll = new JScrollPane(roiList);
    roiScroll.setPreferredSize(GuiUtils.getDimension(200, 80));

    JPanel panel =
        new JPanel(new MigLayout("fillx, insets 5lp 10lp 10lp 10lp", "[right]rel[grow,fill]"));
    panel.setBorder(GuiUtils.getTitledBorder(Messages.getString("TotalSegView.title")));
    panel.add(labelTask);
    panel.add(
        GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, 0, comboTask, buttonRefresh), "growx");
    panel.add(labelOutput, GuiUtils.NEWLINE);
    panel.add(
        GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, 0, comboOutput, fastPanel), "growx");
    panel.add(labelSegType, GuiUtils.NEWLINE);
    panel.add(GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, 0, comboSegType), "growx");
    panel.add(labelRoi, GuiUtils.NEWLINE);
    panel.add(roiScroll, "growx"); // NON-NLS

    panel.setMaximumSize(new Dimension(Short.MAX_VALUE, panel.getPreferredSize().height));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    return panel;
  }

  private void initTaskList() {
    String lastTask = TotalSegFactory.PERSISTENCE.getProperty(LAST_TASK, defaultTaskForSelection());
    checkedRois = readLastRoiSubset();
    // Read before filling the combo, which applies it to the checkbox.
    fastRequested =
        Boolean.parseBoolean(TotalSegFactory.PERSISTENCE.getProperty(LAST_FAST, "false"));
    fillTaskCombo(lastTask);
    comboOutput.setSelectedItem(
        OutputType.fromValue(
            TotalSegFactory.PERSISTENCE.getProperty(LAST_OUTPUT, OutputType.DICOM_SEG.value())));
    comboSegType.setSelectedItem(
        SegType.fromValue(
            TotalSegFactory.PERSISTENCE.getProperty(LAST_SEG_TYPE, SegType.LABELMAP.value())));
    updateSegTypeState();
  }

  /** {@code seg_type} shapes a DICOM SEG only, an RT structure set always holds contours. */
  private void updateSegTypeState() {
    boolean enabled = comboOutput.getSelectedItem() == OutputType.DICOM_SEG;
    comboSegType.setEnabled(enabled);
    labelSegType.setEnabled(enabled);
  }

  private static List<String> readLastRoiSubset() {
    String value = TotalSegFactory.PERSISTENCE.getProperty(LAST_ROI_SUBSET, "");
    if (!StringUtil.hasText(value)) {
      return List.of();
    }
    return Arrays.stream(value.split("\\\\")).filter(StringUtil::hasText).toList();
  }

  private void fillTaskCombo(String selection) {
    comboTask.removeAllItems();
    catalog.taskNames().forEach(comboTask::addItem);
    comboTask.setSelectedItem(selection);
    if (comboTask.getSelectedItem() == null && comboTask.getItemCount() > 0) {
      comboTask.setSelectedIndex(0);
    }
    // Also covers a refresh that kept the selection but changed what the server reports about it.
    taskChanged();
  }

  private void taskChanged() {
    updateRoiList();
    updateFastState();
  }

  /** Only a few tasks have a lower-resolution model, the others reject or ignore the option. */
  private void updateFastState() {
    boolean supported =
        comboTask.getSelectedItem() instanceof String task && catalog.supportsFast(task);
    checkFast.setEnabled(supported);
    checkFast.setSelected(supported && fastRequested);
    checkFast.setToolTipText(supported ? Messages.getString("fast.mode.tooltip") : null);
    fastPanel.setToolTipText(supported ? null : Messages.getString("fast.mode.unsupported"));
  }

  /** MR series default to the MR variant of the whole-body task, everything else to the CT one. */
  private String defaultTaskForSelection() {
    for (TreePath path : treeModel.getCheckingPaths()) {
      if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof MediaSeries<?> series
          && "MR".equals(TagD.getTagValue(series, Tag.Modality, String.class))) {
        return TaskCatalog.DEFAULT_MR_TASK;
      }
    }
    return TaskCatalog.DEFAULT_CT_TASK;
  }

  private void updateRoiList() {
    if (roiList.getModel().getSize() > 0) {
      checkedRois = roiList.getCheckedRois();
    }
    List<String> rois =
        comboTask.getSelectedItem() instanceof String task ? catalog.rois(task) : List.of();
    roiList.setRois(rois, checkedRois);
  }

  private void refreshTasksFromServer() {
    buttonRefresh.setEnabled(false);
    setStatus(Messages.getString("loading.tasks"));
    new SwingWorker<Map<String, TaskInfo>, Void>() {

      @Override
      protected Map<String, TaskInfo> doInBackground() throws Exception {
        return TotalSegConfig.load().newClient().tasks();
      }

      @Override
      protected void done() {
        buttonRefresh.setEnabled(true);
        try {
          Object selection = comboTask.getSelectedItem();
          catalog.update(get());
          fillTaskCombo(selection instanceof String task ? task : defaultTaskForSelection());
          setStatus("");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          LOGGER.warn("Cannot read the TotalSegmentator task list", e);
          setStatus(Messages.getString("server.unreachable"));
        }
      }
    }.execute();
  }

  private void setStatus(String message) {
    GuiExecutor.execute(() -> labelStatus.setText(message));
  }

  /**
   * Registers the segmentation options on the server, then sends the checked series with a C-STORE
   * to the store SCP of the segmentation service.
   */
  @Override
  protected boolean exportAction(List<String> files, DicomProgress dicomProgress) {
    TotalSegConfig config = TotalSegConfig.load();
    DicomNode destination;
    try {
      destination = config.storeNode();
    } catch (TotalSegmentatorException e) {
      LOGGER.error("Cannot resolve the TotalSegmentator DICOM destination", e);
      setStatus(Messages.getString("send.failed"));
      showErrorMessage(e.getMessage());
      return false;
    }

    // Without them the listener would silently segment with its default options.
    try {
      config.newClient().registerDicomParams(checkedSeriesUids(), selectedOptions());
    } catch (IOException e) {
      LOGGER.error("Cannot register the TotalSegmentator options", e);
      setStatus(Messages.getString("params.failed"));
      showErrorMessage(e.getMessage());
      return false;
    }

    AdvancedParams params = new AdvancedParams();
    ConnectOptions connectOptions = new ConnectOptions();
    connectOptions.setConnectTimeout(3000);
    connectOptions.setAcceptTimeout(5000);
    params.setConnectOptions(connectOptions);

    setStatus(String.format(Messages.getString("sending.series"), destination.getAet()));
    DicomState state =
        CStore.process(params, new DicomNode(callingAet()), destination, files, dicomProgress);
    if (state.getStatus() == Status.Cancel) {
      setStatus(Messages.getString("cancelled"));
      return false;
    }
    if (state.getStatus() != Status.Success) {
      LOGGER.error("TotalSegmentator DICOM send failed: {}", state.getMessage());
      setStatus(Messages.getString("send.failed"));
      showErrorMessage(state.getMessage());
      return false;
    }
    LOGGER.info("TotalSegmentator DICOM send: {}", state.getMessage());
    setStatus(String.format(Messages.getString("series.sent"), destination.getAet()));
    return true;
  }

  private SegmentationOptions selectedOptions() {
    return new SegmentationOptions(
        (String) comboTask.getSelectedItem(),
        (OutputType) comboOutput.getSelectedItem(),
        (SegType) comboSegType.getSelectedItem(),
        roiList.getCheckedRois(),
        checkFast.isSelected());
  }

  /** The export keeps the original UIDs, so the listener receives the series under these ones. */
  private List<String> checkedSeriesUids() {
    Set<String> uids = new LinkedHashSet<>();
    for (TreePath path : treeModel.getCheckingPaths()) {
      for (Object component : path.getPath()) {
        if (component instanceof DefaultMutableTreeNode node
            && node.getUserObject() instanceof MediaSeries<?> series) {
          String uid = TagD.getTagValue(series, Tag.SeriesInstanceUID, String.class);
          if (StringUtil.hasText(uid)) {
            uids.add(uid);
          }
        }
      }
    }
    return List.copyOf(uids);
  }

  private static String callingAet() {
    String aet = GuiUtils.getUICore().getSystemPreferences().getProperty("weasis.aet"); // NON-NLS
    return StringUtil.hasText(aet) ? aet : "WEASIS_AE"; // NON-NLS
  }

  private void showErrorMessage(String message) {
    GuiExecutor.execute(
        () ->
            JOptionPane.showMessageDialog(
                WinUtil.getValidComponent(exportTree),
                StringUtil.getTruncatedString(
                    String.valueOf(message), 300, StringUtil.Suffix.THREE_PTS),
                getTitle(),
                JOptionPane.ERROR_MESSAGE));
  }

  private void applyChange() {
    Object task = comboTask.getSelectedItem();
    if (task != null) {
      TotalSegFactory.PERSISTENCE.setProperty(LAST_TASK, task.toString());
    }
    Object output = comboOutput.getSelectedItem();
    if (output instanceof OutputType outputType) {
      TotalSegFactory.PERSISTENCE.setProperty(LAST_OUTPUT, outputType.value());
    }
    Object segType = comboSegType.getSelectedItem();
    if (segType instanceof SegType type) {
      TotalSegFactory.PERSISTENCE.setProperty(LAST_SEG_TYPE, type.value());
    }
    // The choice, not the checkbox, which a task without a fast mode forces to unselected.
    TotalSegFactory.PERSISTENCE.setProperty(LAST_FAST, Boolean.toString(fastRequested));
    TotalSegFactory.PERSISTENCE.setProperty(
        LAST_ROI_SUBSET, String.join("\\", roiList.getCheckedRois()));
  }

  @Override
  public void closeAdditionalWindow() {
    applyChange();
    super.closeAdditionalWindow();
  }
}
