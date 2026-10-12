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

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.param.DicomNode;

/**
 * TotalSegmentator settings, resolved from the system preferences and, for the site-specific values
 * left unset there, from the {@code WEASIS_TOTALSEG_URL}, {@code WEASIS_TOTALSEG_HEADER_NAME} and
 * {@code WEASIS_TOTALSEG_HEADER_VALUE} environment variables. The server URL and the authentication
 * header have no built-in default: they must be provided by the deployment.
 */
public record TotalSegConfig(
    String url,
    String aeTitle,
    int dicomPort,
    String headerName,
    String headerValue,
    int pollIntervalSeconds,
    int maxWaitMinutes,
    boolean deleteResultOnServer) {

  public static final String P_URL = "weasis.totalsegmentator.url";
  public static final String P_AET = "weasis.totalsegmentator.aet";
  public static final String P_DICOM_PORT = "weasis.totalsegmentator.dicom.port";
  public static final String P_HEADER_NAME = "weasis.totalsegmentator.header.name";
  public static final String P_HEADER_VALUE = "weasis.totalsegmentator.header.value";
  public static final String P_POLL_INTERVAL = "weasis.totalsegmentator.poll.interval";
  public static final String P_MAX_WAIT = "weasis.totalsegmentator.max.wait";
  public static final String P_DELETE_RESULT = "weasis.totalsegmentator.delete.result";

  public static final String DEFAULT_AET = "TOTALSEG_SCP"; // NON-NLS
  public static final int DEFAULT_DICOM_PORT = 11112;
  public static final int DEFAULT_POLL_INTERVAL = 5;
  public static final int DEFAULT_MAX_WAIT = 60;
  public static final boolean DEFAULT_DELETE_RESULT = true;

  private static final String ENV_PREFIX = "WEASIS_TOTALSEG_"; // NON-NLS
  public static final String ENV_URL = ENV_PREFIX + "URL"; // NON-NLS
  public static final String ENV_HEADER_NAME = ENV_PREFIX + "HEADER_NAME"; // NON-NLS
  public static final String ENV_HEADER_VALUE = ENV_PREFIX + "HEADER_VALUE"; // NON-NLS

  public static String defaultUrl() {
    return envString(ENV_URL);
  }

  public static String defaultHeaderName() {
    return envString(ENV_HEADER_NAME);
  }

  public static String defaultHeaderValue() {
    return envString(ENV_HEADER_VALUE);
  }

  private static String envString(String name) {
    String value = System.getenv(name);
    return StringUtil.hasText(value) ? value.trim() : "";
  }

  /** An empty preference (e.g. a {@code null} value in {@code base.json}) means "not set". */
  private static String prefString(WProperties prefs, String key, String def) {
    String value = prefs.getProperty(key);
    return StringUtil.hasText(value) ? value.trim() : def;
  }

  public static TotalSegConfig load() {
    WProperties prefs = GuiUtils.getUICore().getSystemPreferences();
    return new TotalSegConfig(
        prefString(prefs, P_URL, defaultUrl()),
        prefString(prefs, P_AET, DEFAULT_AET),
        prefs.getIntProperty(P_DICOM_PORT, DEFAULT_DICOM_PORT),
        prefString(prefs, P_HEADER_NAME, defaultHeaderName()),
        prefString(prefs, P_HEADER_VALUE, defaultHeaderValue()),
        prefs.getIntProperty(P_POLL_INTERVAL, DEFAULT_POLL_INTERVAL),
        prefs.getIntProperty(P_MAX_WAIT, DEFAULT_MAX_WAIT),
        prefs.getBooleanProperty(P_DELETE_RESULT, DEFAULT_DELETE_RESULT));
  }

  /** Store SCP of the segmentation service: its AE title and DICOM port on the API host. */
  public DicomNode storeNode() throws TotalSegmentatorException {
    if (!StringUtil.hasText(url)) {
      throw new TotalSegmentatorException(Messages.getString("no.server.url"));
    }
    String host;
    try {
      host = URI.create(url.trim()).getHost();
    } catch (IllegalArgumentException e) {
      host = null;
    }
    if (!StringUtil.hasText(host)) {
      throw new TotalSegmentatorException(
          String.format(Messages.getString("invalid.server.url"), url));
    }
    return new DicomNode(aeTitle, host, dicomPort);
  }

  public Map<String, String> httpHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", AppProperties.WEASIS_NAME + "/" + AppProperties.WEASIS_VERSION);
    if (StringUtil.hasText(headerName) && StringUtil.hasText(headerValue)) {
      headers.put(headerName.trim(), headerValue.trim());
    }
    return headers;
  }

  public TotalSegmentatorClient newClient() throws TotalSegmentatorException {
    return new TotalSegmentatorClient(
        url, httpHeaders(), Duration.ofSeconds(10), Duration.ofMinutes(30));
  }

  /** Never zero: the polling loop would then spin on the status endpoint. */
  public Duration pollInterval() {
    return Duration.ofSeconds(Math.max(1, pollIntervalSeconds));
  }

  public Duration maxWait() {
    return Duration.ofMinutes(maxWaitMinutes);
  }
}
