package com.oxydroid.xserver.extensions;

import com.oxydroid.xconnector.XInputStream;
import com.oxydroid.xconnector.XOutputStream;
import com.oxydroid.xserver.XClient;
import com.oxydroid.xserver.XServer;
import com.oxydroid.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class Extension {
    public static final byte START_MAJOR_OPCODE = -100;
    private final byte majorOpcode;
    protected final XServer xServer;

    public Extension(XServer xServer, byte majorOpcode) {
        this.xServer = xServer;
        this.majorOpcode = majorOpcode;
    }

    public abstract String getName();

    public byte getMajorOpcode() {
        return majorOpcode;
    }

    public byte getFirstErrorId() {
        return 0;
    }

    public byte getFirstEventId() {
        return 0;
    }

    public abstract void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;
}
