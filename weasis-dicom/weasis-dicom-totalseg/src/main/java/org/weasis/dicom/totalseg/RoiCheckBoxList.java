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
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;

/**
 * List of ROIs where each row carries its own checkbox, so any number of them can be picked without
 * the modifier keys a multi-selection list requires.
 */
public class RoiCheckBoxList extends JList<RoiCheckBoxList.Roi> {

  /** Mutable so that toggling a row does not rebuild the model. */
  public static final class Roi {
    private final String name;
    private boolean checked;

    Roi(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    public boolean isChecked() {
      return checked;
    }
  }

  private final DefaultListModel<Roi> roiModel = new DefaultListModel<>();

  public RoiCheckBoxList() {
    setModel(roiModel);
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    setCellRenderer(new CheckBoxRenderer());
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            int index = locationToIndex(e.getPoint());
            if (index >= 0 && getCellBounds(index, index).contains(e.getPoint())) {
              toggle(index);
            }
          }
        });
    getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleRoi");
    getActionMap()
        .put(
            "toggleRoi",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                toggle(getSelectedIndex());
              }
            });
  }

  private void toggle(int index) {
    if (index >= 0 && index < roiModel.getSize() && isEnabled()) {
      Roi roi = roiModel.get(index);
      roi.checked = !roi.checked;
      repaint();
    }
  }

  /** Replaces the ROIs, keeping the ones of {@code checked} ticked when they are still listed. */
  public void setRois(Collection<String> rois, Collection<String> checked) {
    Set<String> toCheck = new LinkedHashSet<>(checked);
    roiModel.clear();
    for (String name : rois) {
      Roi roi = new Roi(name);
      roi.checked = toCheck.contains(name);
      roiModel.addElement(roi);
    }
    setEnabled(!roiModel.isEmpty());
  }

  /** Empty when nothing is ticked, which asks the server for every ROI of the task. */
  public List<String> getCheckedRois() {
    List<String> checked = new ArrayList<>();
    for (int i = 0; i < roiModel.getSize(); i++) {
      Roi roi = roiModel.get(i);
      if (roi.isChecked()) {
        checked.add(roi.name());
      }
    }
    return List.copyOf(checked);
  }

  private static class CheckBoxRenderer extends JCheckBox implements ListCellRenderer<Roi> {

    @Override
    public Component getListCellRendererComponent(
        JList<? extends Roi> list, Roi value, int index, boolean isSelected, boolean cellHasFocus) {
      setComponentOrientation(list.getComponentOrientation());
      setEnabled(list.isEnabled());
      setFont(list.getFont());
      setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
      setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
      setOpaque(true);
      setText(value.name());
      setSelected(value.isChecked());
      return this;
    }
  }
}
