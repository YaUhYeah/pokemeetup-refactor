package io.github.pokemeetup.multiplayer.service.impl;

import com.badlogic.gdx.Gdx;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import io.github.pokemeetup.NetworkProtocol;
import io.github.pokemeetup.multiplayer.model.ChunkUpdate;
import io.github.pokemeetup.multiplayer.model.PlayerSyncData;
import io.github.pokemeetup.multiplayer.service.MultiplayerClient;
import io.github.pokemeetup.world.model.ObjectType;
import io.github.pokemeetup.world.model.WorldObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MultiplayerClientImpl implements MultiplayerClient {
    private final Map<String, PlayerSyncData> playerStates = new ConcurrentHashMap<>();
    private final Map<String, ChunkUpdate> loadedChunks = new ConcurrentHashMap<>();
    private Client client;
    private boolean connected = false;
    private LoginResponseListener loginResponseListener;
    private CreateUserResponseListener createUserResponseListener;
    private Runnable pendingCreateUserRequest = null;
    private Runnable pendingLoginRequest = null;
    public MultiplayerClientImpl() {
    }

    @Override
    public Map<String, PlayerSyncData> getPlayerStates() {
        return playerStates;
    }

    @Override
    public void setLoginResponseListener(LoginResponseListener listener) {
        this.loginResponseListener = listener;
    }

    @Override
    public void setCreateUserResponseListener(CreateUserResponseListener listener) {
        this.createUserResponseListener = listener;
    }

    @Override
    public void connect(String serverIP, int tcpPort, int udpPort) {
        if (connected) {
            log.warn("Already connected to a server.");
            return;
        }
        client = new Client();
        NetworkProtocol.registerClasses(client.getKryo());

        client.addListener(new Listener() {
            @Override
            public void connected(Connection connection) {
                log.info("Connected to server: {}", connection.getRemoteAddressTCP());
                connected = true;
                if (pendingLoginRequest != null) {
                    pendingLoginRequest.run();
                    pendingLoginRequest = null;
                }
                if (pendingCreateUserRequest != null) {
                    pendingCreateUserRequest.run();
                    pendingCreateUserRequest = null;
                }
            }

            @Override
            public void disconnected(Connection connection) {
                log.info("Disconnected from server: {}", connection.getRemoteAddressTCP());
                connected = false;

                playerStates.clear();
                loadedChunks.clear();

                Gdx.app.postRunnable(() -> {
                    if (loginResponseListener != null) {
                        loginResponseListener.onLoginResponse(false, "Lost connection to server.", "", 0, 0);
                    }
                    if (createUserResponseListener != null) {
                        createUserResponseListener.onCreateUserResponse(false, "Disconnected before completion.");
                    }
                });
            }


            @Override
            public void received(Connection connection, Object object) {
                handleMessage(object);
            }
        });

        try {
            client.start();
            client.connect(5000, serverIP, tcpPort, udpPort);
            log.info("Client attempting to connect to {}:{} (TCP) and {} (UDP)", serverIP, tcpPort, udpPort);
        } catch (IOException e) {
            log.error("Failed to connect to server: {}", e.getMessage(), e);
            if (loginResponseListener != null) {
                loginResponseListener.onLoginResponse(false,
                        "Connection failed: " + e.getMessage(), "", 0, 0);
            }
            if (createUserResponseListener != null) {
                createUserResponseListener.onCreateUserResponse(false,
                        "Connection failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void login(String username, String password) {
        if (!connected) {
            log.warn("Not connected to server. Cannot send login request.");
            if (loginResponseListener != null) {
                loginResponseListener.onLoginResponse(false,
                        "Not connected to server.", "", 0, 0);
            }
            return;
        }
        NetworkProtocol.LoginRequest req = new NetworkProtocol.LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setTimestamp(System.currentTimeMillis());
        client.sendTCP(req);
        log.info("Sent LoginRequest for user: {}", username);
    }

    @Override
    public void createUser(String username, String password) {
        if (!connected) {
            log.warn("Not connected to server. Cannot send create user request.");
            if (createUserResponseListener != null) {
                createUserResponseListener.onCreateUserResponse(false,
                        "Not connected to server.");
            }
            return;
        }
        NetworkProtocol.CreateUserRequest req = new NetworkProtocol.CreateUserRequest();
        req.setUsername(username);
        req.setPassword(password);
        client.sendTCP(req);
        log.info("Sent CreateUserRequest for user: {}", username);
    }

    private void handleMessage(Object object) {
        if (object.getClass().getName().startsWith("com.esotericsoftware.kryonet.FrameworkMessage")) {
            return;
        }

        if (object instanceof NetworkProtocol.LoginResponse resp) {
            log.info("Received LoginResponse: success={}, message={}", resp.isSuccess(), resp.getMessage());
            if (loginResponseListener != null) {
                loginResponseListener.onLoginResponse(
                        resp.isSuccess(),
                        resp.getMessage() != null ? resp.getMessage() : (resp.isSuccess() ? "Success" : "Failed"),
                        resp.getUsername(),
                        resp.getX(),
                        resp.getY()
                );
            }
        } else if (object instanceof NetworkProtocol.CreateUserResponse createResp) {
            log.info("Received CreateUserResponse: success={}, message={}", createResp.isSuccess(), createResp.getMessage());
            if (createUserResponseListener != null) {
                createUserResponseListener.onCreateUserResponse(
                        createResp.isSuccess(),
                        createResp.getMessage() != null ? createResp.getMessage() : (createResp.isSuccess() ? "Account created." : "Failed to create account.")
                );
            }
        } else if (object instanceof NetworkProtocol.PlayerStatesUpdate pUpdate) {
            playerStates.clear();
            playerStates.putAll(pUpdate.getPlayers());
        } else if (object instanceof NetworkProtocol.ChunkData chunkData) {
            ChunkUpdate cUp = new ChunkUpdate();
            cUp.setChunkX(chunkData.getChunkX());
            cUp.setChunkY(chunkData.getChunkY());
            cUp.setTiles(chunkData.getTiles());
            cUp.setObjects(chunkData.getObjects());
            loadedChunks.put(chunkData.getChunkX() + "," + chunkData.getChunkY(), cUp);
        } else if (object instanceof NetworkProtocol.WorldObjectsUpdate wObjects) {
            wObjects.getObjects().forEach(update -> {
                String key = (update.getTileX() / 16) + "," + (update.getTileY() / 16);
                ChunkUpdate cu = loadedChunks.get(key);
                if (cu != null) {
                    if (update.isRemoved()) {
                        cu.getObjects().removeIf(o -> o.getId().equals(update.getObjectId()));
                    } else {
                        boolean found = false;
                        for (WorldObject wo : cu.getObjects()) {
                            if (wo.getId().equals(update.getObjectId())) {
                                wo.setTileX(update.getTileX());
                                wo.setTileY(update.getTileY());
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            ObjectType objType = ObjectType.valueOf(update.getType());
                            WorldObject newObj = new WorldObject(
                                    update.getTileX(),
                                    update.getTileY(),
                                    objType,
                                    objType.isCollidable()
                            );
                            cu.getObjects().add(newObj);
                        }
                    }
                }
            });
        } else {
            log.warn("Unknown message type received: {}", object.getClass().getName());
        }
    }

    @Override
    public void disconnect() {
        if (client != null && connected) {
            client.close();
            connected = false;
            log.info("Client disconnected from server.");
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void sendPlayerMove(float x, float y, boolean running, boolean moving, String direction) {
        if (!connected) return;
        NetworkProtocol.PlayerMoveRequest req = new NetworkProtocol.PlayerMoveRequest();
        req.setX(x);
        req.setY(y);
        req.setRunning(running);
        req.setMoving(moving);
        req.setDirection(direction);
        client.sendTCP(req);
    }

    @Override
    public void requestChunk(int chunkX, int chunkY) {
        if (!connected) return;
        NetworkProtocol.ChunkRequest req = new NetworkProtocol.ChunkRequest();
        req.setChunkX(chunkX);
        req.setChunkY(chunkY);
        req.setTimestamp(System.currentTimeMillis());
        client.sendTCP(req);
    }

    @Override
    public void update(float delta) {
        // Not used in this example.
    }

    @Override
    public void sendMessage(Object msg) {
        if (!connected) return;
        client.sendTCP(msg);
    }

    @Override
    public void setPendingLoginRequest(Runnable action) {
        this.pendingLoginRequest = action;
    }

    @Override
    public void setPendingCreateUserRequest(Runnable action) {
        this.pendingCreateUserRequest = action;
    }
}
