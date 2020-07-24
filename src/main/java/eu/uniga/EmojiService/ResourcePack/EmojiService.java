package eu.uniga.EmojiService.ResourcePack;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.Timer;

import eu.uniga.EmojiService.BitmapGenerator;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;

public class EmojiService
{
	public interface IResourcePackReloadable
	{
		void Reload(String url, String Sha1);
	}
	
	private final Logger _logger = LogManager.getLogger();
	private static final String AtlasName = "discord-emoji";
	public static final Path ResourcePackLocation = Paths.get("resource-pack.zip");
	private final Set<Guild> _servers = new HashSet<>();
	private final Object _serversLock = new Object();
	private List<Emote> _emotes = new ArrayList<>();
	private long _lastEmoteId = 0;
	private final Object _emotesLock = new Object();
	private final Timer _timer;
	
	private BufferedImage _emoteBitmapAtlas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
	private int[][] _emoteCodepointAtlas = new int[0][0];
	private Map<Integer, String> _emoteSurrogatePairsTranslation = new HashMap<>();
	private Map<String, Integer> _emoteIDsTranslation = new HashMap<>();
	private String _hash = "";
	private IResourcePackReloadable _reloadable;
	
	public EmojiService(IResourcePackReloadable reloadable)
	{
		_reloadable = reloadable;
		_timer = new Timer(true);
	}
	
	public void AddChannel(Guild channel)
	{
		synchronized (_serversLock)
		{
			_servers.add(channel);
		}
	}
	
	public void Start(long grabberPeriod)
	{
		Grabber grabber = new Grabber();
		_timer.scheduleAtFixedRate(new Grabber(), 0, grabberPeriod);
	}
	
	public void Stop()
	{
		_timer.cancel();
		_timer.purge();
	}
	
	private byte[] ToByteArray(RenderedImage image) throws IOException
	{
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		ImageIO.write(image, "png", stream);
		stream.close();
		
		return stream.toByteArray();
	}
	
	private @NotNull String Utf16ToEscapedString(int utf16)
	{
		String out = "";
		
		short left = (short)((utf16 & 0xFFFF0000) >> 16);
		short right = (short)utf16;
		
		if (left != 0) out += String.format("\\u%s", String.format("%04x", left));
		out += String.format("\\u%s", String.format("%04x", right));
		
		return out;
	}
	
	private byte[] FormatToByteArray(int[][] codepointAtlas)
	{
		// TODO: move to file
		String json = "{\n" + "  \"providers\": [\n" + "    {\n" + "      \"type\": \"bitmap\",\n" + "      \"file\": \"minecraft:font/%name%.png\",\n" + "      \"ascent\": 7,\n" + "      \"chars\": [\n" + "        %chars%\n" + "      ]\n" + "    }\n" + "  ]\n" + "}";
		StringBuilder formattedAtlas = new StringBuilder();
		
		for (int y = 0; y < codepointAtlas.length; y++)
		{
			formattedAtlas.append("\"");
			
			for (int x = 0; x < codepointAtlas[y].length; x++)
			{
				formattedAtlas.append(Utf16ToEscapedString(codepointAtlas[y][x]));
			}
			
			formattedAtlas.append("\"");
			if (y < codepointAtlas.length - 1) formattedAtlas.append(",");
			formattedAtlas.append(System.lineSeparator());
		}
		
		json = json.replace("%name%", AtlasName);
		json = json.replace("%chars%", formattedAtlas.toString());
		
		return json.getBytes();
	}
	
	private byte[] GetPackMCMeta()
	{
		// TODO: move to file
		String json = "{\n" + "\t\"pack\": {\n" + "\t\t\"pack_format\": 5,\n" + "\t\t\"description\": [\n" + "\t\t\t{\n" + "\t\t\t\t\"text\": \"Autogenerated\",\n" + "\t\t\t\t\"bold\": true,\n" + "\t\t\t\t\"color\": \"#FF0000\"\n" + "\t\t\t}\n" + "\t\t]\n" + "\t}\n" + "}";
		
		return json.getBytes();
	}
	
	private void CreateZip()
	{
		try
		{
			byte[] atlas = ToByteArray(_emoteBitmapAtlas);
			
			Zip zip = new Zip();
			zip.Add("assets/minecraft/textures/font/" + AtlasName + ".png", atlas);
			zip.Add("assets/minecraft/font/default.json", FormatToByteArray(_emoteCodepointAtlas));
			zip.Add("pack.mcmeta", GetPackMCMeta());
			zip.Finish();
			// TODO: try not to read whole file form disk again
			_hash = zip.GetSha1();
		}
		catch (IOException e)
		{
			_logger.warn("Error creating resource pack", e);
		}
	}
	
	public class Grabber extends TimerTask
	{
		@Override
		public void run()
		{
			if (!GetEmotes()) return;
			
			synchronized (_emotesLock)
			{
				BitmapGenerator bitmapGenerator = new BitmapGenerator(_emotes);
				
				_emoteBitmapAtlas = bitmapGenerator.GetEmoteBitmapAtlas();
				_emoteCodepointAtlas = bitmapGenerator.GetEmoteCodepointAtlas();
				_emoteIDsTranslation = bitmapGenerator.GetEmoteIDsTranslation();
				_emoteSurrogatePairsTranslation = bitmapGenerator.GetSurrogatePairsTranslation();
				_emoteIDsTranslation = bitmapGenerator.GetEmoteIDsTranslation();
				CreateZip();
			}
			
			Random random = new Random();
			String url = "http://localhost/resource-pack" + random.nextInt();
			_logger.info("Reloading resource pack, url: " + url + " hash: " + _hash);
			// TODO: thread safety:
			_reloadable.Reload(url, _hash);
		}
		
		private boolean SameEmojiCollections(List<Emote> newEmotes)
		{
			// Assuming "_emotes" is already sorted
			newEmotes.sort(Comparator.comparingLong(ISnowflake::getIdLong));
			
			synchronized (_emotesLock)
			{
				if (_emotes.size() != newEmotes.size()) return false;
				if (_emotes.get(_emotes.size() - 1).getIdLong() != newEmotes.get(newEmotes.size() - 1).getIdLong()) return false;
				
				for (Emote newEmote : newEmotes)
				{
					if (!_emoteIDsTranslation.containsKey(newEmote.getAsMention())) return false;
				}
			}
			
			return true;
		}
		
		private boolean GetEmotes()
		{
			List<Emote> emotes = new ArrayList<>();
			
			synchronized (_serversLock)
			{
				// Get all emotes from servers
				for (Guild server : _servers)
				{
					List<Emote> serverEmotes = server.getEmotes();
					emotes.addAll(serverEmotes);
				}
			}
			
			// Compare if something changed
			if (SameEmojiCollections(emotes)) return false;
			
			synchronized (_emotesLock)
			{
				_emotes = emotes;
			}
			
			return true;
		}
	}
}