package com.botcraft.entity;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * A connection with no Netty channel. Outgoing packets are dropped (otherwise the
 * vanilla connection would queue them forever because it is never "open").
 *
 * <p>1.20.1 specifics (verified against decompiled sources): ServerPlayNetworkHandler#disconnect
 * sends a packet, then runs {@link #handleDisconnection()} on the server thread. Vanilla only
 * notifies the listener when a real channel closed, so we do it ourselves. This makes /kick and
 * server shutdown remove companions cleanly.
 */
public class FakeClientConnection extends ClientConnection {
	@Nullable
	private Text reason;
	private boolean handled;

	public FakeClientConnection() {
		super(NetworkSide.SERVERBOUND);
	}

	@Override
	public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks) {
		// Nobody is listening: drop.
	}

	@Override
	public void disconnect(Text disconnectReason) {
		this.reason = disconnectReason;
	}

	@Override
	public void disableAutoRead() {
	}

	@Override
	public void handleDisconnection() {
		if (handled) {
			return;
		}
		handled = true;
		if (getPacketListener() != null) {
			getPacketListener().onDisconnected(reason != null ? reason : Text.translatable("multiplayer.disconnect.generic"));
		}
	}

	public boolean isHandled() {
		return handled;
	}
}
