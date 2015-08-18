/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.bridge;

/**
 * Bridge exception returns if there is a GCM routing bridge issue.
 */
public class BridgeException extends Exception {

  public BridgeException(final Throwable e) {
    super(e);
  }

  public BridgeException(String msg) {
    super(msg);
  }

  private static final long serialVersionUID = 1;
}
