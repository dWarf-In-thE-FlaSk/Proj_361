package my_game.networking.client;

import com.sun.corba.se.impl.orbutil.closure.Constant;
import java.awt.Color;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import my_game.networking.NetworkEntity;
import my_game.networking.packets.PacketHandler;
import my_game.networking.server.Constants;
import my_game.models.player_components.Player;
import my_game.networking.NetEntityListener;
import my_game.networking.ServerInfo;
import my_game.networking.ServerListListener;
import my_game.networking.packets.impl.GameStatePacket;
import my_game.networking.packets.impl.HelloPacket;
import my_game.networking.packets.impl.ServerInfoPacket;
import my_game.networking.server.GameServer;
import my_game.util.GameException;
import my_game.util.Misc;

/**
 * This is the client code allowing a player to connect to the host/server of a
 * game and communicate with him.
 *
 * @author Ivaylo Parvanov
 *
 */
public class GameClient extends Thread implements NetworkEntity {

    /**
     * Contains information about the client player including username, INET
     * address and port.
     */
    private Player client;
    private ArrayList<NetEntityListener> listeners;
    /**
     * Data stream used to send messages to the connected server.
     */
    private DataOutputStream out = null;
    /**
     * Data stream used to receive messages from the connected server.
     */
    private DataInputStream in = null;
    /**
     * The address of the server.
     */
    private InetAddress serverAddress = null;
    /**
     * A flag for stopping the client thread.
     */
    private boolean clientRunning = false;
    /**
     * A packet handler handling the packets received by the client.
     */
    private PacketHandler packetHandler;
    /**
     * The socket used to connect to the server.
     */
    private Socket clientSocket;

    public GameClient(Player clientPlayer) {
        this.client = clientPlayer;

        //init. listeners list
        listeners = new ArrayList<NetEntityListener>(1);

        //initialise handlers and other objects used by this class
        packetHandler = new PacketHandler(this);
        //Ready to connect!
    }

    /**
     * Connects the client to the specified server and starts the client 
     * thread.
     * @param serverAddress 
     */
    public void connect(InetAddress serverAddress) {
        this.serverAddress = serverAddress;
        
        //start the client thread
        clientRunning = true;
        Thread t = new Thread(new ClientThread());
        t.start();
    }
    
    /**
     * Gathers information about all servers on the LAN network. Since
     * this process is slow, and the servers are retrieved one at a time,
     * this method executes in a separate thread and every time it finds a server,
     * it notifies the ServerListListener about it. This thread can be interrupted
     * by the Thread reference returned.
     * @return The thread created for the server searching is returned.
     */
    public static Thread getLANServersList(final ServerListListener sll) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                InetAddress localhost;
                try {
                    localhost = InetAddress.getLocalHost();
                    // this code assumes IPv4 is used

                    byte[] ip = localhost.getAddress();

                    //if the thread is interrupted the for loop terminates
                    for (int i = 1; i < 255 && !Thread.currentThread().isInterrupted(); i++) {
                        ip[3] = (byte)i;
                        InetAddress address = InetAddress.getByAddress(ip);
                        //make connection to try to reach a server
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(address, Constants.SERVER_INFO_PORT), 75);
                            DataInputStream di = new DataInputStream(s.getInputStream());
                            
                            byte[] data = new byte[1024];
                            //wait to receive a packet
                            di.read(data);
                            //the packet should be a server info packet
                            ServerInfoPacket sip = new ServerInfoPacket(data);
                            //convert received packet into ServerInfo object
                            ServerInfo si = new ServerInfo();
                            si.playerName = sip.playerName;
                            si.serverName = sip.serverName;
                            si.ipAddress = sip.ipAddress;
                            
                            sll.addServerInfo(si);
                            //close socket and stream
                            di.close();
                            s.close();
                        } catch (GameException ex) {
                            Logger.getLogger(GameClient.class.getName()).log(Level.SEVERE, null, ex);
                        } catch(SocketTimeoutException ignore) {}
                        
                    }
                } catch (IOException ex) {
                    Logger.getLogger(GameClient.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
        t.start();
        return t;
    }

    private class ClientThread implements Runnable {
        public void run() {
            //wait for someone to connect
            Misc.log("Server waiting for client...");

            try {
                //open a socket to the server and wait for it to accept the connection
                Misc.log("Client awaiting server to accept connection...");
                clientSocket = new Socket(serverAddress, Constants.SERVER_PORT);
                Misc.log("Client successfully connected to server.");
                //get the input and output streams for communication with the server
                out = new DataOutputStream(clientSocket.getOutputStream());
                in = new DataInputStream(clientSocket.getInputStream());

                //send client's username to the server by creating a hello packet with the username
                sendData(new HelloPacket(client.getUsername()).getData());
            } catch (IOException e) {
                e.printStackTrace();
            }

            //client is listening until the clientRunning flag is set to false
            while (clientRunning) {
                //construct packet object to save received data into
                byte[] data = new byte[1024];

                try {
                    //wait to receive a packet
                    in.read(data);
                    //handle packet
                    packetHandler.handlePacket(data);
                } catch (Exception e) {
                    Misc.log("Exception in client.");
                    clientRunning = false;
                }
            }
            //we are done and it has been requested that the client turns off
            closeClient();

            //end of thread
        }
    }

    /**
     * Closes the socket and streams.
     */
    private void closeClient() {
        Misc.log("Client will now close.");
        try {
            clientSocket.close();
            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Closes the client and stops its thread.
     */
    public void stopClient() {
        clientRunning = false;
    }

    /**
     * @param data Data to send to the server.
     */
    public void sendData(byte[] data) {
        //send packet
        try {
            out.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setOpponentName(String name) {

    }

    @Override
    public void updateGameState(float x, float y, float radius, Color color) {

    }

    @Override
    public void sendGameState(float x, float y, float radius, Color color) {
        
    }
    
    
    
    /**
     * SERVER LIST BUILDING TEST
     * @param args 
     */
    public static void main(String[] args) {
        GameServer s = null;
        try {
            //start a server on this machine
            s = new GameServer(new Player("Player1", "", InetAddress.getLocalHost(), Constants.SERVER_PORT, 1, 0), "Server1");
        } catch (UnknownHostException ex) {
            Logger.getLogger(GameClient.class.getName()).log(Level.SEVERE, null, ex);
        } 
        
        ArrayList<ServerInfo> l = new ArrayList<ServerInfo>();
        Thread t = getLANServersList(new ServerListListener() {

            public void addServerInfo(ServerInfo si) {
                System.out.println("Server info retreived: ");
                System.out.println("Player name: " + si.playerName);
                System.out.println("Server name: " + si.serverName);
                System.out.println("Address: " + si.ipAddress.getHostAddress());
                System.out.println();
            }
        });
        try {
            t.join();
        } catch (InterruptedException ex) {
            Logger.getLogger(GameClient.class.getName()).log(Level.SEVERE, null, ex);
        }
        s.stopServer();
        System.out.println("Main done.");
    }
}
