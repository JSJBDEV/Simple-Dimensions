package lumien.simpledimensions.dimensions;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.UUID;

import org.apache.commons.io.FileUtils;

import lumien.simpledimensions.SimpleDimensions;
import lumien.simpledimensions.network.PacketHandler;
import lumien.simpledimensions.network.messages.MessageDimensionSync;
import lumien.simpledimensions.server.WorldCustom;
import lumien.simpledimensions.util.WorldInfoSimple;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public class DimensionHandler extends WorldSavedData
{
	static String NAME = "SimpleDimensionsHandler";

	HashMap<Integer, WorldInfoSimple> dimensionInfo;
	HashMap<Integer, UUID> toBeDeleted;

	public DimensionHandler(String name)
	{
		super(name);

		dimensionInfo = new HashMap<Integer, WorldInfoSimple>();
		toBeDeleted = new HashMap<Integer, UUID>();
	}

	public DimensionHandler()
	{
		super(NAME);

		dimensionInfo = new HashMap<Integer, WorldInfoSimple>();
		toBeDeleted = new HashMap<Integer, UUID>();
	}
	
	@Override
	public boolean isDirty()
	{
		return true;
	}

	public void createDimension(EntityPlayerMP playerEntity, WorldInfoSimple worldInfo)
	{
		int dimensionID = findFreeDimensionID();

		dimensionInfo.put(dimensionID, worldInfo);

		DimensionManager.registerDimension(dimensionID, SimpleDimensions.INSTANCE.simpleDimensionType);

		loadDimension(dimensionID, worldInfo);

		playerEntity.addChatComponentMessage(new TextComponentString(String.format("Created %s using id %s", worldInfo.getWorldName(), dimensionID)).setStyle(new Style().setColor(TextFormatting.GREEN)));

		syncWithClients();
	}

	private int findFreeDimensionID()
	{
		HashSet<Integer> ids = new HashSet<Integer>();
		ids.addAll(Arrays.asList(DimensionManager.getIDs()));

		int currentID = SimpleDimensions.INSTANCE.config.startDimensionID();
		while (true)
		{
			if (!ids.contains(currentID))
			{
				return currentID;
			}
			else
			{
				currentID++;
			}
		}
	}

	public ITextComponent generateList()
	{
		StringBuilder stringBuilder = new StringBuilder();

		if (dimensionInfo.isEmpty())
		{
			return new TextComponentTranslation("simpleDimensions.nodimensions");
		}
		else
		{
			int counter = 0;
			for (Entry<Integer, WorldInfoSimple> entry : dimensionInfo.entrySet())
			{
				stringBuilder.append(String.format("%s %s", "DIM " + entry.getKey(), "(" + entry.getValue().getWorldName() + ")"));
				counter++;
				if (counter < dimensionInfo.size())
				{
					stringBuilder.append("\n");
				}
			}

			return new TextComponentString(stringBuilder.toString());
		}
	}

	public static DimensionHandler getInstance()
	{
		DimensionHandler INSTANCE;
		INSTANCE = (DimensionHandler) FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld().getMapStorage().getOrLoadData(DimensionHandler.class, NAME);
		
		if (INSTANCE == null)
		{
			INSTANCE = new DimensionHandler();
			FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld().getMapStorage().setData(NAME, INSTANCE);
		}

		return INSTANCE;
	}

	public String getDimensionName(int dimensionId)
	{
		return dimensionInfo.get(dimensionId).getWorldName();
	}

	public HashMap<Integer, WorldInfoSimple> getDimensionInfo()
	{
		return dimensionInfo;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		NBTTagList nbtList = nbt.getTagList("dimensionInfo", 10);

		for (int i = 0; i < nbtList.tagCount(); i++)
		{
			NBTTagCompound compound = nbtList.getCompoundTagAt(i);

			dimensionInfo.put(compound.getInteger("dimensionID"), new WorldInfoSimple(compound.getCompoundTag("worldInfo")));
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList nbtList = new NBTTagList();

		for (Entry<Integer, WorldInfoSimple> entry : dimensionInfo.entrySet())
		{
			NBTTagCompound compound = new NBTTagCompound();

			compound.setInteger("dimensionID", entry.getKey());
			compound.setTag("worldInfo", entry.getValue().cloneNBTCompound(null));

			nbtList.appendTag(compound);
		}

		nbt.setTag("dimensionInfo", nbtList);
		
		return nbt;
	}

	public void loadDimensions()
	{
		for (Entry<Integer, WorldInfoSimple> entry : dimensionInfo.entrySet())
		{
			int dimensionID = entry.getKey();
			WorldInfo worldInfo = entry.getValue();

			DimensionManager.registerDimension(dimensionID, SimpleDimensions.INSTANCE.simpleDimensionType);

			loadDimension(dimensionID, worldInfo);
		}
	}

	private void loadDimension(int dimensionID, WorldInfo worldInfo)
	{
		WorldServer overworld = (WorldServer) FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
		if (overworld == null)
		{
			throw new RuntimeException("Cannot Hotload Dim: Overworld is not Loaded!");
		}
		try
		{
			DimensionManager.getProviderType(dimensionID);
		}
		catch (Exception e)
		{
			System.err.println("Cannot Hotload Dim: " + e.getMessage());
			return;
		}
		
		MinecraftServer mcServer = overworld.getMinecraftServer();
		ISaveHandler savehandler = overworld.getSaveHandler();
		EnumDifficulty difficulty = mcServer.getEntityWorld().getDifficulty();

		WorldServer world = (WorldServer) (new WorldCustom(worldInfo, mcServer, savehandler, dimensionID, overworld, mcServer.theProfiler).init());
		world.addEventListener(new ServerWorldEventHandler(mcServer, world));
		MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));

		if (!mcServer.isSinglePlayer())
		{
			world.getWorldInfo().setGameType(mcServer.getGameType());
		}

		mcServer.setDifficultyForAllWorlds(difficulty);
	}

	public void deleteDimension(ICommandSender sender, int dimensionID)
	{
		if (!dimensionInfo.containsKey(dimensionID))
		{
			sender.addChatMessage(new TextComponentString("The dimension associated with that id is not from the SimpleDimensions mod").setStyle(new Style().setColor(TextFormatting.RED)));
			return;
		}

		World worldObj = DimensionManager.getWorld(dimensionID);

		if (worldObj.playerEntities.size() > 0)
		{
			sender.addChatMessage(new TextComponentString("Can't delete a dimension with players inside it").setStyle(new Style().setColor(TextFormatting.RED)));
			return;
		}

		Entity entitySender = sender.getCommandSenderEntity();
		toBeDeleted.put(dimensionID, entitySender != null ? entitySender.getUniqueID() : null);

		DimensionManager.unloadWorld(dimensionID);
	}

	public void unload(World world, int dimensionID)
	{
		if (dimensionInfo.containsKey(dimensionID))
		{
			WorldInfo worldInfo = dimensionInfo.get(dimensionID);

			DimensionManager.unregisterDimension(dimensionID);
		}

		if (toBeDeleted.containsKey(dimensionID))
		{
			UUID uniqueID = toBeDeleted.get(dimensionID);

			toBeDeleted.remove(dimensionID);
			dimensionInfo.remove(dimensionID);

			((WorldServer) world).flush();
			File dimensionFolder = new File(DimensionManager.getCurrentSaveRootDirectory(), "DIM" + dimensionID);

			EntityPlayerMP player = null;
			if (uniqueID != null)
			{
				player = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayerByUUID(uniqueID);
			}

			try
			{
				FileUtils.deleteDirectory(dimensionFolder);
			}
			catch (IOException e)
			{
				e.printStackTrace();
				if (player != null)
				{
					player.addChatComponentMessage(new TextComponentString("Error deleting dimension folder of " + dimensionID + ". Has to be removed manually.").setStyle(new Style().setColor(TextFormatting.RED)));
				}
			}
			finally
			{
				if (player != null)
				{
					player.addChatComponentMessage(new TextComponentString("Completely deleted dimension " + dimensionID).setStyle(new Style().setColor(TextFormatting.GREEN)));
				}
			}

			syncWithClients();
		}
	}

	private void syncWithClients()
	{
		MessageDimensionSync message = new MessageDimensionSync();

		for (Integer i : dimensionInfo.keySet())
		{
			message.addDimension(i);
		}

		PacketHandler.INSTANCE.sendToAll(message);
	}

	public IMessage constructSyncMessage()
	{
		MessageDimensionSync message = new MessageDimensionSync();

		for (Integer i : dimensionInfo.keySet())
		{
			message.addDimension(i);
		}

		return message;
	}
}
