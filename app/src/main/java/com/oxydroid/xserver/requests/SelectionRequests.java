package com.oxydroid.xserver.requests;

import static com.oxydroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.oxydroid.xconnector.XInputStream;
import com.oxydroid.xconnector.XOutputStream;
import com.oxydroid.xconnector.XStreamLock;
import com.oxydroid.xserver.Atom;
import com.oxydroid.xserver.Window;
import com.oxydroid.xserver.XClient;
import com.oxydroid.xserver.errors.BadAtom;
import com.oxydroid.xserver.errors.BadWindow;
import com.oxydroid.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window owner = client.xServer.windowManager.getWindow(windowId);
        if (owner == null) throw new BadWindow(windowId);
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }
}
