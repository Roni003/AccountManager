package pit.roni.accountmanager;

import pit.roni.accountmanager.auth.Account;
import pit.roni.accountmanager.auth.MicrosoftAuth;
import pit.roni.accountmanager.auth.SessionManager;
import pit.roni.accountmanager.gui.GuiAccountManager;
import pit.roni.accountmanager.utils.Notification;
import pit.roni.accountmanager.utils.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent;
import net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class Events {
  private static final Minecraft mc = Minecraft.getMinecraft();

  private boolean hasAutoLoggedIn = false;
  private Notification autoLoginNotification = null;
  private ExecutorService autoLoginExecutor = null;

  @SubscribeEvent
  public void onRenderTick(TickEvent.RenderTickEvent event) {
    if (event.phase != TickEvent.Phase.END || mc.currentScreen == null) {
      return;
    }

    if (mc.currentScreen instanceof GuiSelectWorld || mc.currentScreen instanceof GuiMultiplayer) {
      String text = TextFormatting.translate(String.format(
        "&7Username: &3%s&r", SessionManager.get().getUsername()
      ));
      GlStateManager.disableLighting();
      mc.currentScreen.drawString(mc.fontRendererObj, text, 2, 2, -1);
    }

    if (mc.currentScreen instanceof GuiMainMenu && autoLoginNotification != null && !autoLoginNotification.isExpired()) {
      String notificationText = autoLoginNotification.getMessage();
      GlStateManager.disableLighting();
      Gui.drawRect(
        mc.currentScreen.width / 2 - mc.fontRendererObj.getStringWidth(notificationText) / 2 - 3, 4,
        mc.currentScreen.width / 2 + mc.fontRendererObj.getStringWidth(notificationText) / 2 + 3, 4 + 3 + mc.fontRendererObj.FONT_HEIGHT + 2,
        0x64000000
      );
      mc.currentScreen.drawCenteredString(mc.fontRendererObj, notificationText, mc.currentScreen.width / 2, 4 + 3, -1);
    }
  }

  @SubscribeEvent
  public void initGuiEvent(InitGuiEvent.Post event) {
    if (event.gui instanceof GuiMainMenu && !hasAutoLoggedIn) {
      hasAutoLoggedIn = true;
      AccountManager.load();
      if (!AccountManager.lastLogin.isEmpty()) {
        Account targetAccount = null;
        for (Account account : AccountManager.accounts) {
          if (AccountManager.lastLogin.equals(account.getUsername())) {
            targetAccount = account;
            break;
          }
        }
        if (targetAccount != null) {
          final Account loginAccount = targetAccount;
          final String username = loginAccount.getUsername();
          final AtomicReference<String> refreshToken = new AtomicReference<>("");
          final AtomicReference<String> accessToken = new AtomicReference<>("");
          autoLoginExecutor = Executors.newSingleThreadExecutor();
          autoLoginNotification = new Notification(TextFormatting.translate(String.format(
            "&7Auto-logging in... (%s)&r", username
          )), -1L);
          MicrosoftAuth.login(loginAccount.getAccessToken(), autoLoginExecutor)
            .handle((session, error) -> {
              if (session != null) {
                loginAccount.setUsername(session.getUsername());
                AccountManager.lastLogin = session.getUsername();
                AccountManager.save();
                SessionManager.set(session);
                autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                  "&aAuto-login successful! (%s)&r", loginAccount.getUsername()
                )), 5000L);
                return true;
              }
              return false;
            })
            .thenComposeAsync(completed -> {
              if (completed) {
                throw new NoSuchElementException();
              }
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&7Refreshing Microsoft tokens... (%s)&r", username
              )), -1L);
              return MicrosoftAuth.refreshMSAccessTokens(loginAccount.getRefreshToken(), autoLoginExecutor);
            })
            .thenComposeAsync(msAccessTokens -> {
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&7Acquiring Xbox access token... (%s)&r", username
              )), -1L);
              refreshToken.set(msAccessTokens.get("refresh_token"));
              return MicrosoftAuth.acquireXboxAccessToken(msAccessTokens.get("access_token"), autoLoginExecutor);
            })
            .thenComposeAsync(xboxAccessToken -> {
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&7Acquiring Xbox XSTS token... (%s)&r", username
              )), -1L);
              return MicrosoftAuth.acquireXboxXstsToken(xboxAccessToken, autoLoginExecutor);
            })
            .thenComposeAsync(xboxXstsData -> {
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&7Acquiring Minecraft access token... (%s)&r", username
              )), -1L);
              return MicrosoftAuth.acquireMCAccessToken(
                xboxXstsData.get("Token"), xboxXstsData.get("uhs"), autoLoginExecutor
              );
            })
            .thenComposeAsync(mcToken -> {
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&7Fetching Minecraft profile... (%s)&r", username
              )), -1L);
              accessToken.set(mcToken);
              return MicrosoftAuth.login(mcToken, autoLoginExecutor);
            })
            .thenAccept(session -> {
              loginAccount.setRefreshToken(refreshToken.get());
              loginAccount.setAccessToken(accessToken.get());
              loginAccount.setUsername(session.getUsername());
              AccountManager.lastLogin = session.getUsername();
              AccountManager.save();
              SessionManager.set(session);
              autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                "&aAuto-login successful! (%s)&r", loginAccount.getUsername()
              )), 5000L);
            })
            .exceptionally(error -> {
              if (!(error.getCause() instanceof NoSuchElementException)) {
                autoLoginNotification = new Notification(TextFormatting.translate(String.format(
                  "&cAuto-login failed: %s&r", error.getMessage()
                )), 5000L);
              }
              return null;
            });
        }
      }
    }

    if (event.gui instanceof GuiSelectWorld || event.gui instanceof GuiMultiplayer) {
      event.buttonList.add(new GuiButton(
        69, 2, 12, 100, 20, "Accounts"
      ));
    }

    if (event.gui instanceof GuiDisconnected) {
      try {
        Field f = ReflectionHelper.findField(GuiDisconnected.class, "message", "field_146304_f");
        IChatComponent message = (IChatComponent) f.get(event.gui);
        String text = message.getFormattedText().split("\n\n")[0];
        if (
          text.equals("§r§cYou are permanently banned from this server!") ||
          text.equals("§r§cYour account has been blocked.")
        ) {
          AccountManager.load();
          for (Account account : AccountManager.accounts) {
            if (mc.getSession().getUsername().equals(account.getUsername())) {
              account.setUnban(-1L);
            }
          }
          AccountManager.save();
          return;
        }

        if (
          text.matches("§r§cYou are temporarily banned for §r§f.*§r§c from this server!") ||
          text.matches("§r§cYour account is temporarily blocked for §r§f.*§r§c from this server!")
        ) {
          String unban = StringUtils.substringBetween(text, "§r§f", "§r§c");
          if (unban != null) {
            long time = System.currentTimeMillis();
            for (String duration : unban.split(" ")) {
              String type = duration.substring(duration.length() - 1);
              long value = Long.parseLong(duration.substring(0, duration.length() - 1));
              switch (type) {
                case "d": {
                  time += value * 86400000L;
                }
                break;
                case "h": {
                  time += value * 3600000L;
                }
                break;
                case "m": {
                  time += value * 60000L;
                }
                break;
                case "s": {
                  time += value * 1000L;
                }
                break;
              }
            }

            AccountManager.load();
            for (Account account : AccountManager.accounts) {
              if (mc.getSession().getUsername().equals(account.getUsername())) {
                account.setUnban(time);
              }
            }
            AccountManager.save();
          }
        }
      } catch (Exception e) {
        //
      }
    }
  }

  @SubscribeEvent
  public void onClick(ActionPerformedEvent event) {
    if (event.gui instanceof GuiSelectWorld || event.gui instanceof GuiMultiplayer) {
      if (event.button.id == 69) {
        mc.displayGuiScreen(new GuiAccountManager(event.gui));
      }
    }
  }

  @SubscribeEvent
  public void onWorldLoad(WorldEvent.Load event) {
    ServerData serverData = mc.getCurrentServerData();
    if (serverData != null) {
      String serverIP = serverData.serverIP;
      if (serverIP.endsWith("hypixel.net") || serverIP.endsWith("hypixel.io")) {
        AccountManager.load();
        for (Account account : AccountManager.accounts) {
          if (mc.getSession().getUsername().equals(account.getUsername())) {
            account.setUnban(0L);
          }
        }
        AccountManager.save();
      }
    }
  }
}
