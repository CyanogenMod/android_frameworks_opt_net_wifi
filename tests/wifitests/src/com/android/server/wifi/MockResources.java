
package com.android.server.wifi;

import java.util.HashMap;

class MockResources extends android.test.mock.MockResources {

    private HashMap<Integer, Boolean> mBooleanValues;
    private HashMap<Integer, Integer> mIntegerValues;
    private HashMap<Integer, String>  mStringValues;

    MockResources() {
        mBooleanValues = new HashMap<Integer, Boolean>();
        mIntegerValues = new HashMap<Integer, Integer>();
        mStringValues  = new HashMap<Integer, String>();
    }

    @Override
    public boolean getBoolean(int id) {
        if (mBooleanValues.containsKey(id)) {
            return mBooleanValues.get(id);
        } else {
            return false;
        }
    }

    @Override
    public int getInteger(int id) {
        if (mIntegerValues.containsKey(id)) {
            return mIntegerValues.get(id);
        } else {
            return 0;
        }
    }

    @Override
    public String getString(int id) {
        if (mStringValues.containsKey(id)) {
            return mStringValues.get(id);
        } else {
            return null;
        }
    }

    public void setBoolean(int id, boolean value) {
        mBooleanValues.put(id, value);
    }

    public void setInteger(int id, int value) {
        mIntegerValues.put(id, value);
    }

    public void setString(int id, String value) {
        mStringValues.put(id, value);
    }
}
