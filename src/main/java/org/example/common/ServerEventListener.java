package org.example.common;
public interface ServerEventListener {
    void onLog(String message);
    void onClientCountChanged(int count);
}