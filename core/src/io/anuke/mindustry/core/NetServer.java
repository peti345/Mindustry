package io.anuke.mindustry.core;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.*;
import io.anuke.mindustry.Vars;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.BulletType;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.entities.TileEntity;
import io.anuke.mindustry.entities.enemies.Enemy;
import io.anuke.mindustry.io.NetworkIO;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.Net.SendMode;
import io.anuke.mindustry.net.Packets.*;
import io.anuke.mindustry.resource.*;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.UCore;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.entities.Entity;
import io.anuke.ucore.modules.Module;
import io.anuke.ucore.util.Bundles;
import io.anuke.ucore.util.Mathf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class NetServer extends Module{
    /**Maps connection IDs to players.*/
    IntMap<Player> connections = new IntMap<>();
    ObjectMap<String, ByteArray> weapons = new ObjectMap<>();
    float serverSyncTime = 4, itemSyncTime = 10, blockSyncTime = 120;
    boolean closing = false;

    public NetServer(){

        Net.handleServer(Connect.class, connect -> UCore.log("Connection found: " + connect.addressTCP));

        Net.handleServer(ConnectPacket.class, packet -> {
            int id = Net.getLastConnection();

            UCore.log("Sending world data to client (ID="+id+")");

            WorldData data = new WorldData();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            NetworkIO.write(stream);

            UCore.log("Packed " + stream.size() + " uncompressed bytes of data.");
            data.stream = new ByteArrayInputStream(stream.toByteArray());

            Net.sendStream(id, data);

            Gdx.app.postRunnable(() -> {
                EntityDataPacket dp = new EntityDataPacket();

                Player player = new Player();
                player.clientid = id;
                player.name = packet.name;
                player.isAndroid = packet.android;
                player.set(Vars.control.core.worldx(), Vars.control.core.worldy() - Vars.tilesize*2);
                player.getInterpolator().last.set(player.x, player.y);
                player.getInterpolator().target.set(player.x, player.y);
                player.add();
                connections.put(id, player);

                dp.playerid = player.id;
                dp.weapons = weapons.get(packet.name, new ByteArray()).toArray();

                Net.sendTo(id, dp, SendMode.tcp);

                sendMessage("[accent]"+Bundles.format("text.server.connected", packet.name));
            });
        });

        Net.handleServer(Disconnect.class, packet -> {
            Player player = connections.get(packet.id);

            if(player == null) {
                sendMessage("[accent]"+Bundles.format("text.server.disconnected", "<???>"));
                return;
            }

            sendMessage("[accent]"+Bundles.format("text.server.disconnected", player.name));
            Gdx.app.postRunnable(player::remove);

            DisconnectPacket dc = new DisconnectPacket();
            dc.playerid = player.id;

            Net.send(dc, SendMode.tcp);
        });

        Net.handleServer(PositionPacket.class, pos -> {
            Player player = connections.get(Net.getLastConnection());
            player.getInterpolator().type.read(player, pos.data);
        });

        Net.handleServer(ShootPacket.class, packet -> {
            Player player = connections.get(Net.getLastConnection());

            Weapon weapon = (Weapon)Upgrade.getByID(packet.weaponid);
            weapon.shoot(player, packet.x, packet.y, packet.rotation);
            packet.playerid = player.id;

            Net.sendExcept(Net.getLastConnection(), packet, SendMode.udp);
        });

        Net.handleServer(PlacePacket.class, packet -> {
            Gdx.app.postRunnable(() -> Vars.control.input.placeBlockInternal(packet.x, packet.y, Block.getByID(packet.block), packet.rotation, true, false));
            packet.playerid = connections.get(Net.getLastConnection()).id;

            Recipe recipe = Recipes.getByResult(Block.getByID(packet.block));
            if(recipe != null){
                for(ItemStack stack : recipe.requirements){
                    Vars.control.removeItem(stack);
                }
            }

            Net.sendExcept(Net.getLastConnection(), packet, SendMode.tcp);
        });

        Net.handleServer(BreakPacket.class, packet -> {
            Gdx.app.postRunnable(() -> Vars.control.input.breakBlockInternal(packet.x, packet.y, false));
            packet.playerid = connections.get(Net.getLastConnection()).id;

            Net.sendExcept(Net.getLastConnection(), packet, SendMode.tcp);
        });

        Net.handleServer(ChatPacket.class, packet -> {
            Player player = connections.get(Net.getLastConnection());

            if(player == null){
                Gdx.app.error("Mindustry", "Could not find player for chat: " + Net.getLastConnection());
                return; //GHOSTS AAAA
            }

            packet.name = player.name;
            packet.id = player.id;
            Net.sendExcept(player.clientid, packet, SendMode.tcp);
            Gdx.app.postRunnable(() -> Vars.ui.chatfrag.addMessage(packet.text, Vars.netClient.colorizeName(packet.id, packet.name)));
        });

        Net.handleServer(UpgradePacket.class, packet -> {
            Player player = connections.get(Net.getLastConnection());

            Weapon weapon = (Weapon)Upgrade.getByID(packet.id);

            if(!weapons.containsKey(player.name)) weapons.put(player.name, new ByteArray());
            if(!weapons.get(player.name).contains(weapon.id)) weapons.get(player.name).add(weapon.id);

            Vars.control.removeItems(UpgradeRecipes.get(weapon));
        });

        Net.handleServer(WeaponSwitchPacket.class, packet -> {
            Player player = connections.get(Net.getLastConnection());

            if(player == null) return;

            packet.playerid = player.id;

            player.weaponLeft = (Weapon)Upgrade.getByID(packet.left);
            player.weaponRight = (Weapon)Upgrade.getByID(packet.right);

            Net.sendExcept(player.clientid, packet, SendMode.tcp);
        });

        Net.handleServer(BlockTapPacket.class, packet -> {
            Tile tile = Vars.world.tile(packet.position);
            tile.block().tapped(tile);

            Net.sendExcept(Net.getLastConnection(), packet, SendMode.tcp);
        });

        Net.handleServer(BlockConfigPacket.class, packet -> {
            Tile tile = Vars.world.tile(packet.position);
            if(tile != null) tile.block().configure(tile, packet.data);

            Net.sendExcept(Net.getLastConnection(), packet, SendMode.tcp);
        });

        Net.handleServer(EntityRequestPacket.class, packet -> {
            int id = packet.id;
            int dest = Net.getLastConnection();
            Gdx.app.postRunnable(() -> {
                if(Vars.control.playerGroup.getByID(id) != null){
                    Net.sendTo(dest, Vars.control.playerGroup.getByID(id), SendMode.tcp);
                    Gdx.app.error("Mindustry", "Replying to entity request: player, " + id);
                }else if (Vars.control.enemyGroup.getByID(id) != null){
                    Enemy enemy = Vars.control.enemyGroup.getByID(id);
                    EnemySpawnPacket e = new EnemySpawnPacket();
                    e.x = enemy.x;
                    e.y = enemy.y;
                    e.id = enemy.id;
                    e.tier = (byte)enemy.tier;
                    e.lane = (byte)enemy.lane;
                    e.type = enemy.type.id;
                    Net.sendTo(dest, e, SendMode.tcp);
                    Gdx.app.error("Mindustry", "Replying to entity request: enemy, " + id);
                }else{
                    Gdx.app.error("Mindustry", "Entity request target not found!");
                }
            });
        });
    }

    public void sendMessage(String message){
        ChatPacket packet = new ChatPacket();
        packet.name = null;
        packet.text = message;
        Net.send(packet, SendMode.tcp);

        Gdx.app.postRunnable(() -> Vars.ui.chatfrag.addMessage(message, null));
    }

    public void handleBullet(BulletType type, Entity owner, float x, float y, float angle, short damage){
        BulletPacket packet = new BulletPacket();
        packet.x = x;
        packet.y = y;
        packet.angle = angle;
        packet.damage = damage;
        packet.owner = owner.id;
        packet.type = type.id;
        Net.send(packet, SendMode.udp);
    }

    public void handleEnemyDeath(Enemy enemy){
        EnemyDeathPacket packet = new EnemyDeathPacket();
        packet.id = enemy.id;
        Net.send(packet, SendMode.tcp);
    }

    public void handleBlockDestroyed(TileEntity entity){
        BlockDestroyPacket packet = new BlockDestroyPacket();
        packet.position = entity.tile.packedPosition();
        Net.send(packet, SendMode.tcp);
    }

    public void handleBlockDamaged(TileEntity entity){
        BlockUpdatePacket packet = new BlockUpdatePacket();
        packet.health = entity.health;
        packet.position = entity.tile.packedPosition();
        Net.send(packet, SendMode.udp);
    }

    public void update(){
        if(!Net.server()) return;

        if(!GameState.is(State.menu) && Net.active()){
            sync();
        }else if(!closing){
            closing = true;
            Vars.ui.loadfrag.show("$text.server.closing");
            Timers.runTask(5f, () -> {
                Net.closeServer();
                Vars.ui.loadfrag.hide();
                closing = false;
            });
        }
    }

    void sync(){

        if(Timers.get("serverSync", serverSyncTime)){
            SyncPacket packet = new SyncPacket();
            int amount = Vars.control.playerGroup.amount() + Vars.control.enemyGroup.amount();
            packet.ids = new int[amount];
            packet.data = new float[amount][0];

            short index = 0;

            for(Player player : Vars.control.playerGroup.all()){
                float[] out = player.getInterpolator().type.write(player);
                packet.data[index] = out;
                packet.ids[index] = player.id;

                index ++;
            }

            packet.enemyStart = index;

            for(Enemy enemy : Vars.control.enemyGroup.all()){
                float[] out = enemy.getInterpolator().type.write(enemy);
                packet.data[index] = out;
                packet.ids[index] = enemy.id;

                index ++;
            }

            Net.send(packet, SendMode.udp);
        }

        if(Timers.get("serverItemSync", itemSyncTime)){
            StateSyncPacket packet = new StateSyncPacket();
            packet.items = Vars.control.items;
            packet.countdown = Vars.control.getWaveCountdown();
            packet.enemies = Vars.control.getEnemiesRemaining();
            packet.wave = Vars.control.getWave();
            packet.time = Timers.time();
            packet.timestamp = TimeUtils.millis();

            Net.send(packet, SendMode.udp);
        }

        if(Timers.get("serverBlockSync", blockSyncTime)){

            IntArray connections = Net.getConnections();

            for(int i = 0; i < connections.size; i ++){
                int id = connections.get(i);
                Player player = this.connections.get(id);
                if(player == null) continue;
                int x = Mathf.scl2(player.x, Vars.tilesize);
                int y = Mathf.scl2(player.y, Vars.tilesize);
                int w = 16;
                int h = 12;
                sendBlockSync(id, x, y, w, h);
            }
        }
    }

    public void sendBlockSync(int client, int x, int y, int viewx, int viewy){
        BlockSyncPacket packet = new BlockSyncPacket();
        ByteArrayOutputStream bs = new ByteArrayOutputStream();

        //TODO compress stream

        try {
            DataOutputStream stream = new DataOutputStream(bs);

            for (int rx = -viewx / 2; rx <= viewx / 2; rx++) {
                for (int ry = -viewy / 2; ry <= viewy / 2; ry++) {
                    Tile tile = Vars.world.tile(x + rx, y + ry);

                    if (tile == null || tile.entity == null) continue;

                    stream.writeInt(tile.packedPosition());
                    byte times = 0;

                    for(; times < tile.entity.timer.getTimes().length; times ++){
                        if(tile.entity.timer.getTimes()[times] <= 1f){
                            break;
                        }
                    }

                    stream.writeByte(times);

                    for(int i = 0; i < times; i ++){
                        stream.writeFloat(tile.entity.timer.getTimes()[i]);
                    }

                    tile.entity.write(stream);
                }
            }

        }catch (IOException e){
            throw new RuntimeException(e);
        }

        packet.stream = new ByteArrayInputStream(bs.toByteArray());

        Net.sendStream(client, packet);
    }
}