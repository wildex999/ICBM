package icbm.explosion.machines;

import icbm.core.ICBMCore;
import icbm.core.implement.IChunkLoadHandler;
import icbm.core.items.ItemSignalDisrupter;
import icbm.core.prefab.TileFrequency;
import icbm.explosion.ICBMExplosion;
import icbm.explosion.entities.EntityMissile;
import icbm.explosion.machines.launcher.TileLauncherPrefab;
import icbm.explosion.machines.launcher.TileLauncherScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraftforge.common.ForgeDirection;
import resonant.api.IRedstoneProvider;
import resonant.api.IRotatable;
import resonant.api.blocks.IBlockFrequency;
import resonant.api.items.IItemFrequency;
import resonant.api.map.IRadarDetectable;
import resonant.api.map.RadarRegistry;
import resonant.lib.multiblock.IBlockActivate;
import resonant.lib.network.IPacketReceiver;
import resonant.lib.network.PacketHandler;
import resonant.lib.utility.LanguageUtility;
import resonant.lib.utility.WrenchUtility;
import universalelectricity.api.energy.EnergyStorageHandler;
import universalelectricity.api.vector.Vector2;
import universalelectricity.api.vector.Vector3;
import calclavia.api.mffs.fortron.FrequencyGrid;

import com.google.common.io.ByteArrayDataInput;

import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.network.Player;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

public class TileRadarStation extends TileFrequency implements IChunkLoadHandler, IPacketReceiver, IRedstoneProvider, IPeripheral, IBlockFrequency, IBlockActivate, IRotatable
{
    public final static int MAX_DETECTION_RANGE = 500;

    public static final float WATTS = 1.5f;
    private final Set<EntityPlayer> playersUsing = new HashSet<EntityPlayer>();
    public float rotation = 0;
    public int alarmRange = 100;
    public int safetyRange = 50;
    public List<Entity> detectedEntities = new ArrayList<Entity>();

    public List<TileEntity> detectedTiles = new ArrayList<TileEntity>();
    public boolean emitAll = true;
    /** List of all incoming missiles, in order of distance. */
    private List<EntityMissile> incomingMissiles = new ArrayList<EntityMissile>();
    private byte fangXiang = 3;

    private Ticket ticket;
    private boolean isPowered = false;
    
    //Classes used when sending entities and tiles to client gui
    public class GUIEntityBase {
    	public byte type;
    	public Vector2 pos;
    	public String name;
    }
    public class GUIEntityMissile extends GUIEntityBase {
    	public boolean willHit;
    	public boolean gotTarget;
    	public int hitX, hitZ;
    }
    public class GUITile {
    	public int posX, posZ;
    	public String name;
    }
    
    public final byte EntityTypePlayer = 0;
    public final byte EntityTypeMissile = 1;
    public final byte EntityTypePlane = 2;
    public final byte EntityTypeUnknown = 3;
    
    public List<GUIEntityBase> entityList = new ArrayList<GUIEntityBase>();
    public List<GUITile> tileList = new ArrayList<GUITile>();
    

    public TileRadarStation()
    {
        super();
        RadarRegistry.register(this);
        setEnergyHandler(new EnergyStorageHandler(500, 400));
    }

    @Override
    public void initiate()
    {
        super.initiate();
        this.worldObj.notifyBlocksOfNeighborChange(this.xCoord, this.yCoord, this.zCoord, this.worldObj.getBlockId(this.xCoord, this.yCoord, this.zCoord));
        this.chunkLoaderInit(ForgeChunkManager.requestTicket(ICBMExplosion.instance, this.worldObj, Type.NORMAL));
    }

    @Override
    public void chunkLoaderInit(Ticket ticket)
    {
        if (!this.worldObj.isRemote)
        {
            if (this.ticket == null && ticket != null)
            {
                this.ticket = ticket;
                new Vector3(this).writeToNBT(this.ticket.getModData());
                ForgeChunkManager.forceChunk(this.ticket, new ChunkCoordIntPair(this.xCoord >> 4, this.zCoord >> 4));
            }
        }
    }

    @Override
    public void updateEntity()
    {
        super.updateEntity();

        if (!this.worldObj.isRemote)
        {
            //Update client every 2 seconds
            if (this.ticks % 40 == 0)
            {
                PacketHandler.sendPacketToClients(this.getDescriptionPacket(), this.worldObj, new Vector3(this), 35);
            }//Send packets to users with the gui open
            else if (this.ticks % 3 == 0)
            {
                for (EntityPlayer player : this.playersUsing)
                {
                    PacketDispatcher.sendPacketToPlayer(this.getDescriptionPacket2(), (Player) player);
                }
            }
        }

        //If we have energy
        if (this.getEnergyHandler().checkExtract())
        {
        	this.isPowered = true;
            this.rotation += 0.08f;

            if (this.rotation > 360)
            {
                this.rotation = 0;
            }

            //Only do check on server and update client over network if in GUI
            if (!this.worldObj.isRemote)
            {
                this.getEnergyHandler().extractEnergy();

	            int prevDetectedEntities = this.detectedEntities.size();
	
	            // Do a radar scan
	            this.doScan();
	
	            if (prevDetectedEntities != this.detectedEntities.size())
	            {
	                this.worldObj.notifyBlocksOfNeighborChange(this.xCoord, this.yCoord, this.zCoord, this.getBlockType().blockID);
	            }
	            
	            //Send entities and tiles to the clients
	            updateClient();
	            
	            //Check for incoming and launch anti-missiles if
	            if (this.ticks % 20 == 0 && this.incomingMissiles.size() > 0)
	            {
	                for (IBlockFrequency blockFrequency : FrequencyGrid.instance().get())
	                {
	                    if (blockFrequency instanceof TileLauncherPrefab)
	                    {
	                        TileLauncherPrefab launcher = (TileLauncherPrefab) blockFrequency;
	
	                        if (new Vector3(this).distance(new Vector3(launcher)) < this.alarmRange && launcher.getFrequency() == this.getFrequency())
	                        {
	                            if (launcher instanceof TileLauncherScreen)
	                            {
	                                double height = launcher.getTarget() != null ? launcher.getTarget().y : 0;
	                                launcher.setTarget(new Vector3(this.incomingMissiles.get(0).posX, height, this.incomingMissiles.get(0).posZ));
	                            }
	                            else
	                            {
	                                launcher.setTarget(new Vector3(this.incomingMissiles.get(0)));
	                            }
	                        }
	                    }
	                }
	            }
            }
        }
        else
        {
        	this.isPowered = false;
            if (detectedEntities.size() > 0)
            {
                worldObj.notifyBlocksOfNeighborChange(this.xCoord, this.yCoord, this.zCoord, this.getBlockType().blockID);
            }

            if(!this.worldObj.isRemote)
            {
	            incomingMissiles.clear();
	            detectedEntities.clear();
	            detectedTiles.clear();
	            
	            //Send empty lists to clients
	            updateClient();
            }
        }

        if (ticks % 40 == 0)
        {
            worldObj.notifyBlocksOfNeighborChange(this.xCoord, this.yCoord, this.zCoord, this.getBlockType().blockID);
        }
    }

    private void doScan()
    {
        this.incomingMissiles.clear();
        this.detectedEntities.clear();
        this.detectedTiles.clear();

        List<Entity> entities = RadarRegistry.getEntitiesWithinRadius(new Vector3(this).toVector2(), MAX_DETECTION_RANGE);

        for (Entity entity : entities)
        {
            if (entity instanceof EntityMissile)
            {
                if (((EntityMissile) entity).feiXingTick > -1)
                {
                    if (!this.detectedEntities.contains(entity))
                    {
                        this.detectedEntities.add(entity);
                    }

                    if (this.isMissileGoingToHit((EntityMissile) entity))
                    {
                        if (this.incomingMissiles.size() > 0)
                        {
                            /** Sort in order of distance */
                            double dist = new Vector3(this).distance(new Vector3(entity));

                            for (int i = 0; i < this.incomingMissiles.size(); i++)
                            {
                                EntityMissile daoDan = this.incomingMissiles.get(i);

                                if (dist < new Vector3(this).distance(new Vector3(daoDan)))
                                {
                                    this.incomingMissiles.add(i, (EntityMissile) entity);
                                    break;
                                }
                                else if (i == this.incomingMissiles.size() - 1)
                                {
                                    this.incomingMissiles.add((EntityMissile) entity);
                                    break;
                                }
                            }
                        }
                        else
                        {
                            this.incomingMissiles.add((EntityMissile) entity);
                        }
                    }
                }
            }
            else
            {
                this.detectedEntities.add(entity);
            }
        }

        List<EntityPlayer> players = this.worldObj.getEntitiesWithinAABB(EntityPlayer.class, AxisAlignedBB.getBoundingBox(this.xCoord - MAX_DETECTION_RANGE, this.yCoord - MAX_DETECTION_RANGE, this.zCoord - MAX_DETECTION_RANGE, this.xCoord + MAX_DETECTION_RANGE, this.yCoord + MAX_DETECTION_RANGE, this.zCoord + MAX_DETECTION_RANGE));

        for (EntityPlayer player : players)
        {
            if (player != null)
            {
                boolean hidden = false;

                for (int i = 0; i < player.inventory.getSizeInventory(); i++)
                {
                    ItemStack itemStack = player.inventory.getStackInSlot(i);

                    if (itemStack != null)
                    {
                        if (itemStack.getItem() instanceof ItemSignalDisrupter)
                        {
                        	ItemSignalDisrupter freqItem = (ItemSignalDisrupter)itemStack.getItem();
                        	
                        	if(freqItem.getEnergy(itemStack) >= freqItem.getEnergyCost())
                        		hidden = true;
                            break;
                        }
                    }
                }

                if (!hidden)
                {
                    this.detectedEntities.add(player);
                }
            }
        }

        for (TileEntity tile : RadarRegistry.getTileEntitiesInArea(new Vector2(this.xCoord - TileRadarStation.MAX_DETECTION_RANGE, this.zCoord - TileRadarStation.MAX_DETECTION_RANGE), new Vector2(this.xCoord + TileRadarStation.MAX_DETECTION_RANGE, this.zCoord + TileRadarStation.MAX_DETECTION_RANGE)))
        {
            if (tile instanceof TileRadarStation)
            {
                if (((TileRadarStation) tile).isPowered)
                {
                    this.detectedTiles.add(tile);
                }
            }
            else
            {
                if (tile instanceof IRadarDetectable)
                {
                    if (((IRadarDetectable) tile).canDetect(this))
                    {
                        this.detectedTiles.add(tile);
                    }
                }
                else
                {
                    this.detectedTiles.add(tile);
                }
            }
        }
    }

    /** Checks to see if the missile will hit within the range of the radar station
     * 
     * @param missile - missile being checked
     * @return true if it will */
    public boolean isMissileGoingToHit(EntityMissile missile)
    {
        if (missile == null || missile.targetVector == null)
        {
            return false;
        }
        return (Vector2.distance(new Vector3(missile).toVector2(), new Vector2(this.xCoord, this.zCoord)) < this.alarmRange && Vector2.distance(missile.targetVector.toVector2(), new Vector2(this.xCoord, this.zCoord)) < this.safetyRange);
    }
    
    //Send an update to any clients with the GUI open, containing entity and tile positions.
    private void updateClient()
    {
    	//If no clients are watching. don't send updates
    	if(this.playersUsing.size() == 0)
    		return;
    	
    	//Create two packets, one for entities and one for tiles,
    	//thus hopefully keeping below packet size limit.
    	
    	//Entity packet(id 5)
    	//entity count(int)
    	//type(byte):
    	//0 - player
    	//1 - missile
    	//2 - plane(TODO)
    	//3 - Unknown
    	//
    	//x(double)
    	//z(double)
    	//name(string)
    	//
    	//Missile:
    	//willHit(bool)
    	//gotTarget(bool)
    	//targetX(int)
    	//targetZ(int)
    	
    	//Tile packet(id 6)
    	//tile count(int)
    	//posX(int)
    	//posZ(int)
    	//Machine name(string)
    	
    	List entityArgs = new ArrayList();
    	entityArgs.add(5); //Packet id
    	entityArgs.add(this.detectedEntities.size()); //Number of entities
    	for(Entity ent : this.detectedEntities)
    	{
    		boolean isMissile = false;
    		
    		//Type
    		if(ent instanceof EntityPlayer)
    		{
    			entityArgs.add(EntityTypePlayer);
    		} else if(ent instanceof EntityMissile) {
    			entityArgs.add(EntityTypeMissile);
    			isMissile = true;
    		} else {
    			entityArgs.add(EntityTypeUnknown);
    		}
    		
    		//Common args
    		entityArgs.add(ent.posX);
    		entityArgs.add(ent.posZ);
    		entityArgs.add(ent.getEntityName());
    		
    		//Missile args
    		if(isMissile)
    		{
    			EntityMissile missile = (EntityMissile)ent;
    			if(this.isMissileGoingToHit(missile))
    				entityArgs.add(true);
    			else
    				entityArgs.add(false);
    			
    			if(missile.targetVector != null)
    			{
    				entityArgs.add(true);
	    			entityArgs.add(missile.targetVector.intX());
	    			entityArgs.add(missile.targetVector.intZ());
    			}
    			else
    				entityArgs.add(false);
    		}
    	}
    	
    	//Send packet with entities
    	Object[] toSend = entityArgs.toArray();
    	for (EntityPlayer player : this.playersUsing)
            PacketDispatcher.sendPacketToPlayer(ICBMCore.PACKET_TILE.getPacket(this, toSend), (Player) player);
    	
    	
    	
    	List tileArgs = new ArrayList();
    	tileArgs.add(6); //Packet id
    	tileArgs.add(this.detectedTiles.size()); //Number of tiles
    	for(TileEntity tile : this.detectedTiles)
    	{
    		tileArgs.add(tile.xCoord);
    		tileArgs.add(tile.zCoord);
    		
    		Block blockType = tile.getBlockType();
    		if(blockType != null)
    		{
    			if(blockType instanceof BlockICBMMachine)
    			{
    				tileArgs.add(BlockICBMMachine.getJiQiMing(tile));
    			}
    			else
    			{
    				tileArgs.add(blockType.getLocalizedName());
    			}
    		}
    		else
    		{
    			tileArgs.add("Unknown");
    		}
    	}
    	
    	//Send packet with tiles
    	toSend = tileArgs.toArray();
    	for (EntityPlayer player : this.playersUsing)
            PacketDispatcher.sendPacketToPlayer(ICBMCore.PACKET_TILE.getPacket(this, toSend), (Player) player);
    	
    }

    private Packet getDescriptionPacket2()
    {
        return ICBMCore.PACKET_TILE.getPacket(this, 1, this.alarmRange, this.safetyRange, this.getFrequency());
    }

    @Override
    public Packet getDescriptionPacket()
    {
        return ICBMCore.PACKET_TILE.getPacket(this, 4, this.fangXiang, this.getEnergyHandler().getEnergy());
    }

    @Override
    public void onReceivePacket(ByteArrayDataInput data, EntityPlayer player, Object... extra)
    {
        try
        {
            final int ID = data.readInt();

            if (ID == -1)
            {
                if (data.readBoolean())
                {
                    PacketHandler.sendPacketToClients(this.getDescriptionPacket2(), this.worldObj, new Vector3(this), 15);
                    this.playersUsing.add(player);
                }
                else
                {
                    this.playersUsing.remove(player);
                }
            }
            else if (this.worldObj.isRemote)
            {
            	switch(ID)
            	{
            	case 1:
                    this.alarmRange = data.readInt();
                    this.safetyRange = data.readInt();
                    this.setFrequency(data.readInt());
            		break;
            	case 4:
            		this.fangXiang = data.readByte();
                    this.getEnergyHandler().setEnergy(data.readLong());
            		break;
            	case 5:
            		readNetworkEntities(data);
            		break;
            	case 6:
            		readNetworkTiles(data);
            		break;
            	}
            }
            else if (!this.worldObj.isRemote)
            {
                if (ID == 2)
                {
                    this.safetyRange = data.readInt();
                }
                else if (ID == 3)
                {
                    this.alarmRange = data.readInt();
                }
                else if (ID == 4)
                {
                    this.setFrequency(data.readInt());
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    public void readNetworkEntities(ByteArrayDataInput data)
    {
    	entityList.clear();
    	int entityCount = data.readInt();
    	
    	if(entityCount <= 0)
    		return;
    	
    	while(entityCount-- > 0)
    	{
    		byte type = data.readByte();
    		GUIEntityBase currentEntity;
    		if(type == EntityTypeMissile)
    			currentEntity = new GUIEntityMissile();
    		else
    			currentEntity = new GUIEntityBase();
    		
    		//Common values
    		currentEntity.pos = new Vector2(data.readDouble(), data.readDouble());
    		currentEntity.name = data.readUTF();
    		
    		if(type == EntityTypeMissile)
    		{
    			GUIEntityMissile missile = (GUIEntityMissile)currentEntity;
    			missile.willHit = data.readBoolean();
    			missile.gotTarget = data.readBoolean();
    			
    			if(missile.gotTarget)
    			{
	    			missile.hitX = data.readInt();
	    			missile.hitZ = data.readInt();
    			}
    		}
    		
    		entityList.add(currentEntity);
    	}
    }
    
    public void readNetworkTiles(ByteArrayDataInput data)
    {
    	tileList.clear();
    	int tileCount = data.readInt();
    	
    	if(tileCount <= 0)
    		return;
    	
    	while(tileCount-- > 0)
    	{
    		GUITile currentTile = new GUITile();
    		currentTile.posX = data.readInt();
    		currentTile.posZ = data.readInt();
    		currentTile.name = data.readUTF();
    		
    		tileList.add(currentTile);
    	}
    }

    @Override
    public boolean isPoweringTo(ForgeDirection side)
    {
        if (incomingMissiles.size() > 0)
        {
            if (this.emitAll)
            {
                return true;
            }

            for (EntityMissile incomingMissile : this.incomingMissiles)
            {
                Vector2 position = new Vector3(incomingMissile).toVector2();
                ForgeDirection missileTravelDirection = ForgeDirection.UNKNOWN;
                double closest = -1;

                for (int i = 2; i < 6; i++)
                {
                    double dist = Vector2.distance(position, new Vector2(this.xCoord + ForgeDirection.getOrientation(i).offsetX, this.zCoord + ForgeDirection.getOrientation(i).offsetZ));

                    if (dist < closest || closest < 0)
                    {
                        missileTravelDirection = ForgeDirection.getOrientation(i);
                        closest = dist;
                    }
                }

                if (missileTravelDirection.getOpposite() == side)
                {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean isIndirectlyPoweringTo(ForgeDirection side)
    {
        return this.isPoweringTo(side);
    }

    /** Reads a tile entity from NBT. */
    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        super.readFromNBT(nbt);
        this.safetyRange = nbt.getInteger("safetyBanJing");
        this.alarmRange = nbt.getInteger("alarmBanJing");
        this.emitAll = nbt.getBoolean("emitAll");
        this.fangXiang = nbt.getByte("fangXiang");
    }

    /** Writes a tile entity to NBT. */
    @Override
    public void writeToNBT(NBTTagCompound nbt)
    {
        super.writeToNBT(nbt);
        nbt.setInteger("safetyBanJing", this.safetyRange);
        nbt.setInteger("alarmBanJing", this.alarmRange);
        nbt.setBoolean("emitAll", this.emitAll);
        nbt.setByte("fangXiang", this.fangXiang);
    }

    @Override
    public boolean onActivated(EntityPlayer entityPlayer)
    {
        if (entityPlayer.inventory.getCurrentItem() != null)
        {
            if (WrenchUtility.isUsableWrench(entityPlayer, entityPlayer.inventory.getCurrentItem(), this.xCoord, this.yCoord, this.zCoord))
            {
                if (!this.worldObj.isRemote)
                {
                    this.emitAll = !this.emitAll;
                    entityPlayer.addChatMessage(LanguageUtility.getLocal("message.radar.redstone") + " " + this.emitAll);
                }

                return true;
            }
        }
        if(!this.worldObj.isRemote)
        	entityPlayer.openGui(ICBMExplosion.instance, 0, this.worldObj, this.xCoord, this.yCoord, this.zCoord);
        return true;
    }

    @Override
    public String getType()
    {
        return "ICBMRadar";
    }

    @Override
    public String[] getMethodNames()
    {
        return new String[] { "getEntities", "getBlocks" };
    }

    @Override
    public Object[] callMethod(IComputerAccess computer, ILuaContext context, int method, Object[] arguments) throws Exception
    {
        if (!this.isPowered)
        {
            throw new Exception("Radar has insufficient electricity!");
        }

        HashMap<String, Double> returnArray = new HashMap<String, Double>();
        int count = 0;

        switch (method)
        {
            case 0:
                List<Entity> entities = RadarRegistry.getEntitiesWithinRadius(new Vector3(this).toVector2(), this.alarmRange);

                for (Entity entity : entities)
                {
                    returnArray.put("x_" + count, entity.posX);
                    returnArray.put("y_" + count, entity.posY);
                    returnArray.put("z_" + count, entity.posZ);
                    count++;
                }

                return new Object[] { returnArray };
            case 1:
                for (TileEntity jiQi : RadarRegistry.getTileEntitiesInArea(new Vector2(this.xCoord - TileRadarStation.MAX_DETECTION_RANGE, this.zCoord - TileRadarStation.MAX_DETECTION_RANGE), new Vector2(this.xCoord + TileRadarStation.MAX_DETECTION_RANGE, this.zCoord + TileRadarStation.MAX_DETECTION_RANGE)))
                {
                    returnArray.put("x_" + count, (double) jiQi.xCoord);
                    returnArray.put("y_" + count, (double) jiQi.yCoord);
                    returnArray.put("z_" + count, (double) jiQi.zCoord);
                    count++;
                }
                return new Object[] { returnArray };
        }

        throw new Exception("Invalid ICBM Radar Function.");
    }

    @Override
    public void attach(IComputerAccess computer)
    {

    }

    @Override
    public void detach(IComputerAccess computer)
    {

    }

    @Override
    public void invalidate()
    {
        ForgeChunkManager.releaseTicket(this.ticket);
        RadarRegistry.unregister(this);
        super.invalidate();
    }

    @Override
    public ForgeDirection getDirection()
    {
        return ForgeDirection.getOrientation(this.fangXiang);
    }

    @Override
    public void setDirection(ForgeDirection facingDirection)
    {
        this.fangXiang = (byte) facingDirection.ordinal();
    }

    @Override
    public boolean equals(IPeripheral other)
    {
    	//Still not sure what the purpose of this method is
    	//so we just do a reference comparison
        return this == other;
    }

}
