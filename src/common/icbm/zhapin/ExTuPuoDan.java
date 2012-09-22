package icbm.zhapin;

import net.minecraft.src.Entity;
import net.minecraft.src.World;
import net.minecraftforge.common.ForgeDirection;
import universalelectricity.prefab.Vector3;
import universalelectricity.recipe.RecipeManager;

public class ExTuPuoDan extends ZhaPin
{
	public ExTuPuoDan(String name, int ID, int tier)
	{
		super(name, ID, tier);
		this.setFuse(10);
	}

	@Override
	public void doExplosion(World worldObj, Vector3 position, Entity explosionSource)
	{
		Condensed.doExplosion(worldObj, position, explosionSource);

		Vector3 difference = new Vector3();
		
		if(explosionSource instanceof EZhaDan)
		{
			difference.modifyPositionFromSide(((EZhaDan)explosionSource).getDirection());
		}
		else
		{
			difference.modifyPositionFromSide(ForgeDirection.DOWN);
		}
		
		for(int i = 0; i < 6; i ++)
		{
			position.add(difference);
			position.add(difference);
			Condensed.doExplosion(worldObj, position, explosionSource);
		}
    }
	
	@Override
	public void addCraftingRecipe()
	{
        RecipeManager.addRecipe(this.getItemStack(2), new Object [] {"@", "@", "@", '@', Condensed.getItemStack()});
	}
}