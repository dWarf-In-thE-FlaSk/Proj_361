package my_game.networking.packets.impl;

import java.awt.Color;
import my_game.models.game_components.GameState;

import my_game.networking.packets.Packet;

/**
 *
 * @author Ivo
 */
public class GameStatePacket extends Packet {
    
    public GameStatePacket(GameState gs) {
        super(PacketTypes.GAMESTATE.getId());
        //TODO implement
    }

    @Override
    public byte[] getData() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
}
