package com.oxydroid.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
