/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package my_game.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import my_game.gui.GameGUI;
import my_game.gui.GameGUI.Action;
import my_game.models.game_components.CoralReef;
import my_game.models.game_components.GameObject;
import my_game.models.game_components.GameState;
import my_game.models.game_components.GameState.GamePhase;
import my_game.models.game_components.Map;
import my_game.models.game_components.Ship;
import my_game.models.game_components.ShipUnit;
import my_game.models.player_components.Message;
import my_game.models.player_components.Player;
import my_game.models.ships_impl.MineLayer;
import my_game.networking.NetEntityListener;
import my_game.networking.NetworkEntity;
import my_game.util.GameException;
import my_game.util.Misc;
import my_game.util.Positions;
import my_game.util.TurnPositions;
import my_game.util.Vector2;

    
/**
 * This class is a controller of the game. One instance
 * of this class runs at the host and one at the client, 
 * both of these communicating amongst them via networking
 * entities (GameServer and GameClient).
 */
public class Game implements GameGUI.GameGuiListener {    
       
    public enum PlayerType {
        Host, Client
    };
    
    /** The Player who is running this instance of the Game controller. */
    private final Player player;
    /** The opponent of the player who is running this instance of the Game controller. */
    private final Player opponent;
    /** Network object used for communication with the opponent. */
    private final NetworkEntity net;
    /** The game state contains a full description of the game that is currently being played. */
    private GameState gameState, receivedGameState;
    private boolean receivedNewGamestate = false;
    
    /** The player type is a flag indicating whether the player running the instance
     * of this Game object is the host of the game, or a client connected to the host. */
    private final Game.PlayerType playerType;
    
    private final ServerListener sListener;
    private final ClientListener cListener;
    
    /** This variable contains a ship selected by the user. */
    private Ship selectedShip;
    /** A reference to the game gui which is displaying the game itself. */
    private GameGUI gui;
    //// DIFFERENT TYPES OF HIGHLIGHTS DISPLAYED IN GUI
    private Positions moveHighlight;
    private TurnPositions turnHighlight;
    private ArrayList<Vector2> weaponHighlight;
    //////*************************************
    private boolean awaitingInput, hasTurn, enemyShipSelectionAllowed;
    private boolean actionTakenThisTurn;
    private Vector2 input;
    
    public Game(Player player, Player opponent, CoralReef reef, NetworkEntity net, Game.PlayerType playerType, String name) {
        this(player, opponent, reef, net, playerType, name, 0);
    }
    
    /**
     * When a new game is constructed, this constructor also initializes all
     * data structures necessary for the new game.
     * @param player Player who is creating this object.
     * @param opponent Player who is connected to this game via the network.
     * @param reef Coral reef used for the game.
     * @param net Network entity used for connection to the other player.
     * @param playerType Type of the player: host or client
     * @param name The name of the game.
     * @param startingPlayer The player who starts first.
     */
    public Game(Player player, Player opponent, CoralReef reef, NetworkEntity net, 
            Game.PlayerType playerType, String name, int startingPlayer) {
        //init local fields
        this.player = player;
        this.opponent = opponent;
        this.net = net;
        this.playerType = playerType;
        
        if(playerType == Game.PlayerType.Host) {
            sListener = new ServerListener();
            cListener = null;
            
            net.addNetListener(sListener);
            
            //generate the index of the first player if an invalid index is passed.
            int firstPlayer = startingPlayer;
            if(startingPlayer < 0 || startingPlayer > 1) {
                firstPlayer = getRandomFirstPlayer();
            }
            //init game state
            gameState = new GameState(new Player[] {player, opponent}, reef, firstPlayer, name);
            
            //TODO if the line below is commented, uncomment for proper behaviour
            sendGameState();
            startGame();
        } else {
            //add a listener to the client
            sListener = null;
            cListener = new ClientListener();
            
            net.addNetListener(cListener);
            
            System.out.println("Client will now wait to receive game state.");
            //You are a client. Wait to receive a game state from server
            synchronized(player) {
                while(!receivedNewGamestate) {
                    try {
                        player.wait();
                    } catch (InterruptedException ex) {
                        Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            System.out.println("Client received a game state.");
            this.gameState = receivedGameState;
            receivedNewGamestate = false;
            
            startGame();
        }
    } 
    
    /**
     * A listener for the server, if this player is a host.
     */
    private class ServerListener implements NetEntityListener {

        public void onConnected() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onReefReceive(CoralReef reef) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onVoteReceive(boolean vote) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onGameStateReceive(GameState gs) {
            //use player as a common synchronization object
            synchronized(player) {
                receivedGameState = new GameState(gs);
                if(receivedGameState != null) {
                    //replace the partial information about this player in the gamestate with the full info
                    receivedGameState.setPlayer(0, player);
                    receivedNewGamestate = true;
                }
                player.notifyAll();
            }
        }
    }
    
    /**
     * A listener for the client, if this player joined the game of a host.
     */
    public class ClientListener implements NetEntityListener {

        public void onConnected() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onReefReceive(CoralReef reef) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onVoteReceive(boolean vote) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void onGameStateReceive(GameState gs) {
            //use player as a common synchronization object
            synchronized(player) {
                receivedGameState = new GameState(gs);
                if(receivedGameState != null) {
                    //replace the partial information about this player in the gamestate with the full info
                    receivedGameState.setPlayer(1, player);
                    receivedNewGamestate = true;
                }
                player.notifyAll();
            }
        }
        
    }

    /* **************  GameGuiListener implemented methods ******************* */
    
    @Override
    public void initializeComplete() {
        //if someone is waiting on this monitor, notify that gui is initialized
        synchronized(this) {
            this.notifyAll();
        }
    }
    
    @Override
    public void onMouseClick(int x, int y) {
        //if it's the player's turn and there is a ship under the mouse cursor, select the ship
        Vector2 position = new Vector2(x, y);
        if(!hasTurn) {
            //do nothing, it is not the turn of this player
        } else if(!awaitingInput && gameState.getMap().isShip(position) && gui.isVisible(x, y)) {
            //first clear all previous highlights for previously selected ships
            clearGUI();
            //mark the ship as selected and enable the gui buttons
            ShipUnit s = (ShipUnit) gameState.getMap().getObjectAt(position);
            Ship ship = s.getShip();
            this.selectedShip = ship;
            //highlight the selected ship in the gui
            gui.highlightPositions(Map.getShipPositions(selectedShip));
            //enable the buttons only if in the player turns phase
            if(gameState.getPhase().equals(GamePhase.PlayerTurns) && gameState.getCurrentPlayer().equals(player)) {
                gui.setActionButtonsEnabled(true);
                if(!ship.getShipType().equals(Ship.ShipType.MineLayer)) {
                    //this is not a mine layer so disable the mine button
                    gui.mineActivated = false;
                }
            } else {
                //this is not the player turns phase which means there could be a thread
                //waiting for a ship to be selected, notify all such threads
                synchronized(this) {
                    this.notifyAll();
                }
            }
        } else if (awaitingInput) {
            synchronized(this) {
                input = new Vector2(position);
                this.notifyAll();
                //check if the player clicked on a new ship
                if(gui.isVisible(x, y) && gameState.getMap().isShip(position)) {
                    ShipUnit s = (ShipUnit) gameState.getMap().getObjectAt(position);
                    Ship ship = s.getShip();
                    this.selectedShip = ship;
                    //highlight the selected ship in the gui
                    clearGUI();
                    gui.highlightPositions(Map.getShipPositions(selectedShip));
                }
            }
        } else {
            clearGUI();
        }
    }
    
    @Override
    public void onButtonPressed(Action action) {
        if(gameState.getPhase().equals(GamePhase.ShipPositioning)) {
            switch(action) {
                case EndTurn:
                    //the end turn button ends the ship positioning phase
                    gameState.setGamePhase(GamePhase.ShipPositioningDone);
                    gui.setAllButtonsEnabled(false);
                    synchronized(this) {
                        this.notifyAll();
                    }
                    break;
                default:
                    //only end turn is active during ship positioning
                    break;
            }
        } else if(gameState.getPhase().equals(GamePhase.PlayerTurns)) {
            if(awaitingInput) {
                synchronized(this) {
                    input = null;
                    this.notifyAll();
                }
            }
            if(!hasTurn) {
                return;
            }
            if(selectedShip != null) {
                //we can take an action, there's a ship already selected
                Thread t;
                switch(action) {
                    case Move:
                        interruptPreviousActions();
                        t = new Thread(new Runnable() {
                            public void run() {
                                moveAction(selectedShip);
                            }
                        });
                        t.start();
                        break;
                    case Turn:
                        interruptPreviousActions();
                        t = new Thread(new Runnable() {
                            public void run() {
                                turnAction(selectedShip);
                            }
                        });
                        t.start();
                        break;
                    case CannonAttack:
                        interruptPreviousActions();
                        t = new Thread(new Runnable() {
                            public void run() {
                                cannonAttackAction(selectedShip);
                            }
                        });
                        t.start();
                        break;
                    case EndTurn:
                        interruptPreviousActions();
                        endTurn();
                        break;
                    case Mine:
                        interruptPreviousActions();
                        t = new Thread(new Runnable() {
                            public void run() {
                                if(gui.mineActivated) {
                                    layMine(selectedShip);
                                }
                            }
                        });
                        t.start();
                        break;
                    default:
                        Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, new GameException("Unknown action encountered."));
                        break;
                }
            } else if(action.equals(Action.EndTurn)) {
                    interruptPreviousActions();
                    endTurn();
            }
        }
    }
    
    /**
     * Notifies all action threads that are awaiting position input, and sets the
     * input to null, as the user has chosen not to enter position, but do something else instead.
     */
    private void interruptPreviousActions() {
         synchronized(this) {
            input = null;
            this.notifyAll();
        }
        clearGUI();
    }

    /* *********************************************************************** */
    
    
    /**
     * Places the provided ship at the specified coordinates of the map.
     * @param s
     * @param x
     * @param y 
     */

        
    /**
     * Initialises the gui and sets up the game.
     */
    private void startGame() {
        //init and display GUI
        gui = new GameGUI(30, 30, this, this.player);
        gui.start();
        //now wait until the gui initializes
        synchronized(this) {
            try {
                this.wait();
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        gui.drawGameState(this.gameState);
        enemyShipSelectionAllowed = false;
        //positioning phase
        positionShips();
        
        gameState.setGamePhase(GamePhase.PlayerTurns);
        while(!gameState.gameOver()) {
            startTurn();
            waitForGameState();
            //TODO for now we are simply assigning the game state as it is received
            // but in future check for consistency and consider the server's game
            //state as the primary one
            gameState = receivedGameState;
            receivedNewGamestate = false;
            if(gameState.previousAction == null) {
                gui.drawGameState(gameState);
            } else {
                gui.updateGameState(gameState);
            }
        }
    }
    
    /**
     * Allows the player to position his ships around the base as much as he wants,
     * sends the updated ship positions to the other party and then waits for a
     * reply with the other player's ship positions.
     */
    private void positionShips() {
        gameState.setGamePhase(GameState.GamePhase.ShipPositioning);
        hasTurn = true;
        
        //light up the end turn button only
        activateEndTurnButton();
        
        while(gameState.getPhase().equals(GamePhase.ShipPositioning)) {
            synchronized(this) {
                try {
                    while(selectedShip == null && gameState.getPhase().equals(GamePhase.ShipPositioning)) {
                        this.wait();
                    }

                    //do an intermediate check whether the game phase has changed
                    if(gameState.getPhase().equals(GamePhase.ShipPositioning)) {
                        
                        //now we have a selected ship, get a location to move the ship to
                        awaitingInput = true;
                        this.wait();
                        awaitingInput = false;
                        if(gameState.getPhase().equals(GamePhase.ShipPositioning) && gameState.positionShip(selectedShip, input)) {
                            //ship positionning was successful, update gui
                            gui.drawGameState(gameState);
                            selectedShip = null;
                        }
                    } else {
                        selectedShip = null;
                    }
                } catch(InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        
        if(playerType.equals(PlayerType.Client)) {
            //the client first sends the gamestate then waits for a merged final
            //game state from the server
            sendGameState();
            //wait the server's finalized copy
            waitForGameState();
            //set the received game state 
            this.gameState = receivedGameState;
            receivedNewGamestate = false;
        } else {
            //this is the server, wait for the client's state
            waitForGameState();
            receivedNewGamestate = false;
            //now we've received a new gamestate from the other party; merge with 
            //the current gamestate
            gameState.mergeShipPositions(this.player, receivedGameState);
            //send back a copy of the finalized state to the client
            sendGameState();
        }
        
        //redraw game state
        gui.drawGameState(gameState);
    }
    
    /**
     * This method is responsible of displaying the game over interface.
     */
    private void gameOver() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    
    /**
     * Chooses randomly between 0 and 1, then returns the result.
     * @return 
     */
    private int getRandomFirstPlayer() {
        double r = Math.random();
        if(r < 0.5) {
            return 0;
        } else {
            return 1;
        }
    }
    
    /**
     * If the game state says that this player has the current turn, this method
     * prepares the gui for the player's turn.
     */
    private void startTurn() {
        Player playerWithTurn = gameState.getPlayer(gameState.getPlayerTurn());
        if(playerWithTurn.equals(player)) {
            //this player's turn
            hasTurn = true;
            activateEndTurnButton();
        } else {
            hasTurn = false;
        }
        actionTakenThisTurn = false;
    }
    
    private void endTurn() {
        //set turn to next player
        gameState.nextTurn();
        if(!actionTakenThisTurn) {
            gameState.previousAction = null;
        }
        //send the game state
        sendGameState();
        //disable buttons
        gui.setAllButtonsEnabled(false);
        selectedShip = null;
        if(gameState.gameOver()) {
            gameOver();
        }
        hasTurn = false;
        //TODO insert other stuff to do at the end of a turn
    }
    
    public void moveAction(Ship s){
        //gather the available move positions for the specified ship
        moveHighlight = gameState.getMap().prepareMoveShip(s);
        //pass these positions to the gui to be displayed
        gui.highlightPositions(moveHighlight);
       
        synchronized(this) {    //synchronized because we are waiting the 'awaitingInput' flag to be changed
            try {
                awaitingInput = true;
                this.wait();
                awaitingInput = false;
                
                //check if the result is acceptable
                if(input != null && gameState.moveShip(s, input, moveHighlight)) {
                    //action successfully executed
                    Message m = new Message("Ship moved to new position " + input + ".", Message.MessageType.Game, player);
                    gameState.addMessage(m);
                    //clear up the gui
                    clearGUI();
                    //draw the movement animation by calling the update method in gui
                    gui.updateGameState(gameState);
                    actionTakenThisTurn = true;
                    endTurn();
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    public void turnAction(Ship s){
        //gather all positions where the specified ship can turn to
        turnHighlight = gameState.getMap().prepareTurnShip(s);
        //provide these positions to gui for displaying
        gui.highlightPositions(turnHighlight);
       
        synchronized(this) {
            try {
                awaitingInput = true;
                this.wait();
                awaitingInput = false;

                if(input != null && gameState.getMap().turnShip(s, input, turnHighlight)) {
                    //action successfully executed
                    gui.drawGameState(gameState);   //draw updated gameState
                    Message m = new Message("Ship turned to new position " + input + ".", Message.MessageType.Game, player);
                    gameState.addMessage(m);
                    //clear up the gui
                    clearGUI();
                    actionTakenThisTurn = true;
                    endTurn();
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }      
    }
            
    private void cannonAttackAction(Ship s) {
        //need to be called on the map object.
        weaponHighlight = s.getCannonPositions();

        // TO DO: pass these positions to GUI and get user's selection in Vector2 newPosition)
        gui.highlightPositions(weaponHighlight);
       
        synchronized(this) {
            try {
                awaitingInput = true;
                this.wait();
                awaitingInput = false;
                if(input != null) {
                    //check if the result is acceptable
                    boolean found = false;
                    for(Vector2 v: weaponHighlight) {
                        if(v.equals(input)) {
                            found = true;
                        }
                    }

                    GameObject targetHit = gameState.cannonAttack(s, input);
                    if(targetHit != null && found) {
                        Message m = new Message("Cannon impact at coordinates: " + gameState.getMap().objectCoordinates(targetHit), Message.MessageType.Game, null);
                        //Message m = new Message("Cannon impact at : " + ((ShipUnit) targetHit).unitArmour + " " + ((ShipUnit) targetHit).damageLevel, Message.MessageType.Game, null);
                        gameState.addMessage(m);
                        gui.drawGameState(gameState);
                        //clear up the gui
                        clearGUI();
                        actionTakenThisTurn = true;
                        endTurn();
                    } else if(!found) {
                        clearGUI();
                    } else {
                        Misc.log("No hit.");
                        clearGUI();
                        endTurn();
                    }
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    /**
     * A small helper method that clears the gui at the end of an action.
     */
    private void clearGUI() {
        gui.requestClearHighlight();
    }
        
    /**
     * Blocks the current thread until a game state is received.
     */
    private void waitForGameState() {
        synchronized(player) {
            try {
                while(!receivedNewGamestate || receivedGameState == null) {
                    player.wait();
                }
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    public void layMine(Ship s){
    	MineLayer miner = (MineLayer) s;
        Vector2[] mineZone = gameState.getMap().getFilteredMineDropZone(s);
        
        gui.highlightPositions(new ArrayList<Vector2> (Arrays.asList(mineZone)));
        synchronized(this) {
            try {
                awaitingInput = true;
                this.wait();
                awaitingInput = false;
                if(input != null) {
                    //check if the result is acceptable
                    boolean found = false;
                    for(Vector2 v: mineZone) {
                        if(v.equals(input)) {
                            found = true;
                        }
                    }
                    
                    if(!found) {
                        clearGUI();
                        return;
                    }
                    //now if the player clicked on a mine, it should be picked.
                    //if there was no mine, a new one should be placed
                    if(gameState.getMap().isMine(input)) {
                        //pickup mine
                        Message m = new Message("Mine picked up.", Message.MessageType.Game, this.player);
                        gameState.addMessage(m);
                        gameState.pickupMine(miner, input);
                    } else {
                        Message m = new Message("Mine layed.", Message.MessageType.Game, this.player);
                        gameState.addMessage(m);
                        gameState.layMine(s, input);
                    }
                    gui.drawGameState(gameState);
                    clearGUI();
                    actionTakenThisTurn = true;
                    endTurn();
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    public void fireCannon(GameObject unit){
    	//can be called by any type of ship
    	//TO DO: highlight the available ship and cannonRange to GUI pass the (user selected)attacker and targeting position to map
    	//map.cannonAttack(Ship attacker, Vector2 position)
    }
    
    public void fireTorpedo(GameObject unit){
    	//can only be called by destroyer and torpedo boat
    	//TO DO: highlight the available ship and torpedoRange to GUI pass the (user selected)attacker and targeting position to map
    	//map.TorpedoAttack(Ship attacker, Vector2 position)
    }
    
    /**
     * Sends the game state to other connected players.
     */
    private void sendGameState() {
        net.sendGameState(this.gameState);
    }
    
    /**
     * Lights up only the end turn button.
     */
    private void activateEndTurnButton() {
        gui.setAllButtonsEnabled(true);
        gui.setActionButtonsEnabled(false);
    }
}
