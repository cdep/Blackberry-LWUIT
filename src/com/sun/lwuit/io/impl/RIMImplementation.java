/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores
 * CA 94065 USA or visit www.oracle.com if you need additional information or
 * have any questions.
 */

package com.sun.lwuit.io.impl;

import com.sun.lwuit.io.NetworkManager;
import java.io.IOException;
import java.util.Vector;

import javax.microedition.io.Connection;
import javax.microedition.io.Connector;

import net.rim.device.api.servicebook.ServiceBook;
import net.rim.device.api.servicebook.ServiceRecord;
import net.rim.device.api.system.DeviceInfo;

/**
 * Implementation targeting RIM devices to support their IAP and elaborate
 * network picking policy described
 * 
 * @author Shai Almog
 */
public class RIMImplementation extends MIDPImpl {

	private static final boolean FORCE_NOT_SIMULATOR = false;

	public static final int CONNECTION_TYPE_NOT_SET = 0;
	public static final int CONNECTION_TYPE_BIS = 1;
	public static final int CONNECTION_TYPE_WAP2 = 2;
	public static final int CONNECTION_TYPE_DEVICE_SIDE_TRUE = 3;
	public static final int CONNECTION_TYPE_DEVICE_SIDE_FALSE = 4;
	public static final int CONNECTION_TYPE_JUST_URL = 5;
	public static final int CONNECTION_TYPE_WIFI = 6;

	private static final int[] connectionTypes = { CONNECTION_TYPE_NOT_SET, CONNECTION_TYPE_BIS, CONNECTION_TYPE_WAP2, CONNECTION_TYPE_DEVICE_SIDE_FALSE, CONNECTION_TYPE_JUST_URL, CONNECTION_TYPE_DEVICE_SIDE_TRUE, CONNECTION_TYPE_WIFI };
	private static int connectionTypeIndex = 0;
	private static int connectionType = CONNECTION_TYPE_NOT_SET;
	private static String connectionTypeHistory = "";

	private String currentAccessPoint;
	private boolean deviceSide;

	/**
	 * @inheritDoc
	 */
	public boolean isAPSupported() {
		return true;
	}

	private Vector getValidSBEntries() {
		Vector v = new Vector();
		ServiceBook bk = ServiceBook.getSB();
		ServiceRecord[] recs = bk.getRecords();
		for (int iter = 0; iter < recs.length; iter++) {
			ServiceRecord sr = recs[iter];
			if (sr.isValid() && !sr.isDisabled() && sr.getUid() != null
					&& sr.getUid().length() != 0) {
				v.addElement(sr);
			}
		}
		return v;
	}

	/**
	 * @inheritDoc
	 */
	public String[] getAPIds() {
		Vector v = getValidSBEntries();
		String[] s = new String[v.size()];
		for (int iter = 0; iter < s.length; iter++) {
			s[iter] = "" + ((ServiceRecord) v.elementAt(iter)).getUid();
		}
		return s;
	}

	/**
	 * @inheritDoc
	 */
	public int getAPType(String id) {
		Vector v = getValidSBEntries();
		for (int iter = 0; iter < v.size(); iter++) {
			ServiceRecord r = (ServiceRecord) v.elementAt(iter);
			if (("" + r.getUid()).equals(id)) {
				if (r.getUid().toLowerCase().indexOf("wifi") > -1) {
					return NetworkManager.ACCESS_POINT_TYPE_WLAN;
				}
				// wap2
				if (r.getCid().toLowerCase().indexOf("wptcp") > -1) {
					return NetworkManager.ACCESS_POINT_TYPE_NETWORK3G;
				}
				return NetworkManager.ACCESS_POINT_TYPE_UNKNOWN;
			}
		}
		return NetworkManager.ACCESS_POINT_TYPE_UNKNOWN;
	}

	/**
	 * @inheritDoc
	 */
	public String getAPName(String id) {
		Vector v = getValidSBEntries();
		for (int iter = 0; iter < v.size(); iter++) {
			ServiceRecord r = (ServiceRecord) v.elementAt(iter);
			if (("" + r.getUid()).equals(id)) {
				return r.getName();
			}
		}
		return null;
	}

	/**
	 * @inheritDoc
	 */
	public String getCurrentAccessPoint() {
		if (currentAccessPoint != null) {
			return currentAccessPoint;
		}
		return null;
	}

	/**
	 * @inheritDoc
	 */
	public void setCurrentAccessPoint(String id) {
		currentAccessPoint = id;
		int t = getAPType(id);
		deviceSide = t == NetworkManager.ACCESS_POINT_TYPE_NETWORK3G || t == NetworkManager.ACCESS_POINT_TYPE_WLAN;
	}

	static {
		connectionType = getNextConnectionType();
	}

	public static String getConnectionTypeHistory() {
		return connectionTypeHistory;
	}

	private static int getNextConnectionType() {
		int next = -1;
		if (connectionTypeIndex + 1 < connectionTypes.length)
			next = connectionTypes[++connectionTypeIndex];
		else
			next = connectionTypes[connectionTypeIndex = 0];
		connectionTypeHistory += getConnectionTypeName(next) + ", ";
		return next;
	}

	public static String getConnectionTypeName(int connectionType) {
		switch (connectionType) {
		case CONNECTION_TYPE_NOT_SET:
			return "CONNECTION_TYPE_NOT_SET";
		case CONNECTION_TYPE_BIS:
			return "CONNECTION_TYPE_BIS";
		case CONNECTION_TYPE_WAP2:
			return "CONNECTION_TYPE_WAP2";
		case CONNECTION_TYPE_JUST_URL:
			return "CONNECTION_TYPE_JUST_URL";
		case CONNECTION_TYPE_DEVICE_SIDE_TRUE:
			return "CONNECTION_TYPE_DEVICE_SIDE_TRUE";
		case CONNECTION_TYPE_DEVICE_SIDE_FALSE:
			return "CONNECTION_TYPE_DEVICE_SIDE_FALSE";
		case CONNECTION_TYPE_WIFI:
			return "CONNECTION_TYPE_WIFI";
		}
		return "null";
	}

	private static String getWap2Uid() {

		ServiceBook sb = ServiceBook.getSB();
		ServiceRecord[] records = sb.getRecords();

		for (int i = 0; i < records.length; i++) {
			// Search through all service records to find the valid
			// BIS service.
			if (records[i].isValid() && !records[i].isDisabled() && records[i].getUid() != null && records[i].getUid().length() != 0) {
				String uid = records[i].getUid().toUpperCase();
				String name = records[i].getName().toUpperCase();
				String cid = records[i].getCid().toUpperCase();
				if (cid.indexOf("WPTCP") >= 0 && uid.indexOf("WAP2") > 0)

				{
					return records[i].getUid();
				}

			}

		}
		return null;
	}

	/**
	 * @inheritDoc
	 */
	public Object connect(String url, boolean read, boolean write) throws IOException {

		Connection connection = null;
		if (FORCE_NOT_SIMULATOR || !DeviceInfo.isSimulator()) {

			try {
				String connectionString = null;
				switch (connectionType) {
				case CONNECTION_TYPE_BIS:
					connectionString = url + ";deviceside=false;ConnectionType=mds-public";
					// Dialog.alert("BIS");
					break;
				case CONNECTION_TYPE_WAP2:
					connectionString = url + ";deviceside=true;ConnectionUID=" + this.currentAccessPoint;
					// Dialog.alert("WAP");
					break;
				case CONNECTION_TYPE_DEVICE_SIDE_TRUE:
					connectionString = url + ";deviceside=true";
					// Dialog.alert("OTHER");
					break;
				case CONNECTION_TYPE_DEVICE_SIDE_FALSE:
					connectionString = url + ";deviceside=false";
					// Dialog.alert("OTHER");
					break;
				case CONNECTION_TYPE_JUST_URL:
					connectionString = url;
					// Dialog.alert("OTHER");
					break;
				case CONNECTION_TYPE_WIFI:
					connectionString = url + ";deviceside=true;interface=wifi";
					// Dialog.alert("OTHER");
					break;
				case CONNECTION_TYPE_NOT_SET:
					throw new IOException();
				}

				connection = (Connection) super.connect(connectionString, read, write);

			} catch (Exception e) {
				if (connectionType != CONNECTION_TYPE_NOT_SET) {
					connectionType = getNextConnectionType();
					return connect(url, read, write);
				} else {
					throw new IOException();
				}
			}
		} else {
			try {
				connection = Connector.open(url + ";deviceside=true");
			} catch (Exception e) {
				throw new IOException();
			}
		}
		return connection;
	}
}
