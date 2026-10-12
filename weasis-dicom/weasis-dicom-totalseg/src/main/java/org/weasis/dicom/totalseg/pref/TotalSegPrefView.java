/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.totalseg.pref;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.totalseg.Messages;
import org.weasis.dicom.totalseg.TotalSegConfig;

/** Where the TotalSegmentator API lives and how long Weasis waits for it. */
public class TotalSegPrefView extends AbstractItemDialogPage {

  private static final Logger LOGGER = LoggerFactory.getLogger(TotalSegPrefView.class);

  private final JTextField textUrl = new JTextField(24);
  private final JTextField textAet = new JTextField(12);
  private final JFormattedTextField textDicomPort = new JFormattedTextField();
  private final JTextField textHeaderName = new JTextField(12);
  private final JTextField textHeaderValue = new JTextField(18);
  private final JSpinner spinnerPoll = new JSpinner();
  private final JSpinner spinnerMaxWait = new JSpinner();
  private final JCheckBox checkDeleteResult = new JCheckBox(Messages.getString("delete.result"));
  private final JButton buttonTest = new JButton(Messages.getString("test.connection"));
  private final JLabel labelTestResult = new JLabel();

  public TotalSegPrefView() {
    super(Messages.getString("TotalSegPrefView.title"), 650);
    initGUI();
  }

  private void initGUI() {
    TotalSegConfig config = TotalSegConfig.load();
    textUrl.setText(config.url());
    textUrl.setCaretPosition(0);
    textAet.setText(config.aeTitle());
    GuiUtils.formatPortTextField(textDicomPort);
    textDicomPort.setValue(config.dicomPort());
    textHeaderName.setText(config.headerName());
    textHeaderValue.setText(config.headerValue());
    GuiUtils.setNumberModel(spinnerPoll, config.pollIntervalSeconds(), 1, 300, 1);
    GuiUtils.setNumberModel(spinnerMaxWait, config.maxWaitMinutes(), 1, 720, 5);
    checkDeleteResult.setSelected(config.deleteResultOnServer());
    checkDeleteResult.setToolTipText(Messages.getString("delete.result.tooltip"));
    buttonTest.addActionListener(_ -> testConnection());

    JPanel panel =
        new JPanel(new MigLayout("fillx, insets 5lp 10lp 10lp 10lp", "[right]rel[grow,fill]"));
    panel.setBorder(GuiUtils.getTitledBorder(Messages.getString("TotalSegPrefView.title")));

    panel.add(new JLabel(Messages.getString("server.url") + StringUtil.COLON));
    panel.add(textUrl, "growx"); // NON-NLS

    JLabel labelDestination =
        new JLabel(Messages.getString("dicom.destination") + StringUtil.COLON);
    labelDestination.setToolTipText(Messages.getString("dicom.destination.tooltip"));
    panel.add(labelDestination, GuiUtils.NEWLINE);
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            ITEM_SEPARATOR_SMALL,
            0,
            new JLabel(Messages.getString("called.aet") + StringUtil.COLON),
            textAet,
            new JLabel(Messages.getString("dicom.port") + StringUtil.COLON),
            textDicomPort),
        "growx"); // NON-NLS

    JLabel labelHeader = new JLabel(Messages.getString("http.header") + StringUtil.COLON);
    labelHeader.setToolTipText(Messages.getString("http.header.tooltip"));
    panel.add(labelHeader, GuiUtils.NEWLINE);
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            ITEM_SEPARATOR_SMALL,
            0,
            new JLabel(Messages.getString("header.name") + StringUtil.COLON),
            textHeaderName,
            new JLabel(Messages.getString("header.value") + StringUtil.COLON),
            textHeaderValue),
        "growx"); // NON-NLS

    panel.add(new JLabel(Messages.getString("poll.interval") + StringUtil.COLON), GuiUtils.NEWLINE);
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            ITEM_SEPARATOR_SMALL,
            0,
            spinnerPoll,
            new JLabel(Messages.getString("seconds")),
            GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR_LARGE),
            new JLabel(Messages.getString("max.wait") + StringUtil.COLON),
            spinnerMaxWait,
            new JLabel(Messages.getString("minutes"))),
        "growx"); // NON-NLS

    panel.add(checkDeleteResult, "newline, spanx 2, alignx leading"); // NON-NLS
    panel.add(
        GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, 0, buttonTest, labelTestResult),
        "newline, spanx 2, alignx leading"); // NON-NLS

    panel.setMaximumSize(new Dimension(Short.MAX_VALUE, panel.getPreferredSize().height));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    add(panel);

    add(GuiUtils.boxYLastElement(LAST_FILLER_HEIGHT));
  }

  /** Checks the URL and the optional header without leaving the preferences. */
  private void testConnection() {
    buttonTest.setEnabled(false);
    labelTestResult.setText("");
    TotalSegConfig config = currentConfig();
    new SwingWorker<String, Void>() {

      @Override
      protected String doInBackground() throws Exception {
        return config.newClient().health();
      }

      @Override
      protected void done() {
        buttonTest.setEnabled(true);
        try {
          labelTestResult.setText(String.format(Messages.getString("connection.ok"), get()));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          LOGGER.warn("TotalSegmentator connection test failed", e);
          labelTestResult.setText(
              Messages.getString("connection.failed") + StringUtil.COLON_AND_SPACE + message(e));
        }
      }
    }.execute();
  }

  private static String message(Exception e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    return StringUtil.getTruncatedString(
        String.valueOf(cause.getMessage()), 80, StringUtil.Suffix.THREE_PTS);
  }

  private int dicomPort() {
    Number port = GuiUtils.getFormattedValue(textDicomPort);
    return port == null ? TotalSegConfig.DEFAULT_DICOM_PORT : port.intValue();
  }

  private TotalSegConfig currentConfig() {
    return new TotalSegConfig(
        textUrl.getText().trim(),
        textAet.getText().trim(),
        dicomPort(),
        textHeaderName.getText().trim(),
        textHeaderValue.getText().trim(),
        (Integer) spinnerPoll.getValue(),
        (Integer) spinnerMaxWait.getValue(),
        checkDeleteResult.isSelected());
  }

  @Override
  public void closeAdditionalWindow() {
    TotalSegConfig config = currentConfig();
    WProperties prefs = GuiUtils.getUICore().getSystemPreferences();
    prefs.setProperty(TotalSegConfig.P_URL, config.url());
    prefs.setProperty(TotalSegConfig.P_AET, config.aeTitle());
    prefs.putIntProperty(TotalSegConfig.P_DICOM_PORT, config.dicomPort());
    prefs.setProperty(TotalSegConfig.P_HEADER_NAME, config.headerName());
    prefs.setProperty(TotalSegConfig.P_HEADER_VALUE, config.headerValue());
    prefs.putIntProperty(TotalSegConfig.P_POLL_INTERVAL, config.pollIntervalSeconds());
    prefs.putIntProperty(TotalSegConfig.P_MAX_WAIT, config.maxWaitMinutes());
    prefs.putBooleanProperty(TotalSegConfig.P_DELETE_RESULT, config.deleteResultOnServer());
    GuiUtils.getUICore().saveSystemPreferences();
  }

  @Override
  public void resetToDefaultValues() {
    textUrl.setText(TotalSegConfig.defaultUrl());
    textUrl.setCaretPosition(0);
    textAet.setText(TotalSegConfig.DEFAULT_AET);
    textDicomPort.setValue(TotalSegConfig.DEFAULT_DICOM_PORT);
    textHeaderName.setText(TotalSegConfig.defaultHeaderName());
    textHeaderValue.setText(TotalSegConfig.defaultHeaderValue());
    spinnerPoll.setValue(TotalSegConfig.DEFAULT_POLL_INTERVAL);
    spinnerMaxWait.setValue(TotalSegConfig.DEFAULT_MAX_WAIT);
    checkDeleteResult.setSelected(TotalSegConfig.DEFAULT_DELETE_RESULT);
    labelTestResult.setText("");
  }
}
