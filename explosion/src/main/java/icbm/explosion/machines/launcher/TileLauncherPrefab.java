package icbm.explosion.machines.launcher;

import icbm.core.prefab.TileFrequency;
import net.minecraft.nbt.NBTTagCompound;
import resonant.api.explosion.ILauncherController;
import resonant.api.explosion.LauncherType;
import universalelectricity.api.vector.Vector3;
import calclavia.api.mffs.fortron.FrequencyGrid;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

public abstract class TileLauncherPrefab extends TileFrequency implements ILauncherController, IPeripheral
{
    protected Vector3 targetPos = null;

    @Override
    public void initiate()
    {
        super.initiate();
        FrequencyGrid.instance().register(this);
    }

    @Override
    public void invalidate()
    {
        FrequencyGrid.instance().unregister(this);
        super.invalidate();
    }

    @Override
    public Vector3 getTarget()
    {
        if (this.targetPos == null)
        {
            if (this.getLauncherType() == LauncherType.CRUISE)
            {
                this.targetPos = new Vector3(this.xCoord, this.yCoord, this.zCoord);
            }
            else
            {
                this.targetPos = new Vector3(this.xCoord, 0, this.zCoord);
            }
        }

        return this.targetPos;
    }

    @Override
    public void setTarget(Vector3 target)
    {
        this.targetPos = target.floor();
    }

    @Override
    public String getType()
    {
        return "ICBMLauncher";
    }

    @Override
    public String[] getMethodNames()
    {
        return new String[] { "launch", "getTarget", "setTarget", "canLaunch", "setFrequency", "getFrequency", "getMissile" };
    }

    @Override
    public Object[] callMethod(IComputerAccess computer, ILuaContext context, int method, Object[] arguments) throws Exception
    {
        switch (method)
        {
            case 0:
                this.launch();
                return null;
            case 1:
                return new Object[] { this.getTarget().x, this.getTarget().y, this.getTarget().z };
            case 2:
                if (arguments[0] != null && arguments[1] != null && arguments[2] != null)
                {
                    try
                    {
                        this.setTarget(new Vector3(((Double) arguments[0]).doubleValue(), ((Double) arguments[1]).doubleValue(), ((Double) arguments[2]).doubleValue()));
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        throw new Exception("Target Parameter is Invalid.");
                    }
                }
                return null;
            case 3:
                return new Object[] { this.canLaunch() };
            case 4:
                if (arguments[0] != null)
                {
                    try
                    {
                        double arg = ((Double) arguments[0]).doubleValue();
                        arg = Math.max(Math.min(arg, Short.MAX_VALUE), Short.MIN_VALUE);
                        this.setFrequency((short) arg);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        throw new Exception("Frequency Parameter is Invalid.");
                    }
                }
                return null;
            case 5:
                return new Object[] { this.getFrequency() };
            case 6:
                if (this.getMissile() != null)
                {
                    return new Object[] { this.getMissile().getExplosiveType().getMissileName() };
                }
                else
                {
                    return null;
                }
        }

        throw new Exception("Invalid ICBM Launcher Function.");
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
    public void readFromNBT(NBTTagCompound nbt)
    {
        super.readFromNBT(nbt);
        this.targetPos = new Vector3(nbt.getCompoundTag("target"));
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt)
    {
        super.writeToNBT(nbt);

        if (this.targetPos != null)
        {
            nbt.setCompoundTag("target", this.targetPos.writeToNBT(new NBTTagCompound()));
        }
    }

    @Override
    public boolean equals(IPeripheral other)
    {
    	//Still not sure what the purpose of this method is
    	//so we just do a reference comparison
        return this == other;
    }
}
