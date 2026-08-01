package com.winlator.cmod.xserver;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xserver.extensions.SyncExtension;

public class XClientConnectionHandler implements ConnectionHandler {
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new XClient(xServer, client.getInputStream(), client.getOutputStream()));
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        XClient xClient = (XClient)client.getTag();

        SyncExtension syncExtension = xServer.getExtension(SyncExtension.MAJOR_OPCODE);
        if (syncExtension != null) syncExtension.freeFencesOwnedBy(xClient);

        xClient.freeResources();
    }
}