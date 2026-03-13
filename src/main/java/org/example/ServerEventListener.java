package org.example;
public interface ServerEventListener {
    void onLog(String message);
    void onClientCountChanged(int count);
}