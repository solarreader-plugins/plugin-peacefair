/*
 * Copyright (c) 2024-2025 Stefan Toengi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.schnippsche.solarreader.plugins.peacefair;

import com.fazecast.jSerialComm.SerialPort;
import de.schnippsche.solarreader.backend.command.Command;
import de.schnippsche.solarreader.backend.command.SendCommand;
import de.schnippsche.solarreader.backend.connection.general.ConnectionFactory;
import de.schnippsche.solarreader.backend.connection.modbus.ModbusConnection;
import de.schnippsche.solarreader.backend.connection.modbus.ModbusConnectionFactory;
import de.schnippsche.solarreader.backend.connection.modbus.ModbusRegisterType;
import de.schnippsche.solarreader.backend.connection.usb.SerialUsbConnection;
import de.schnippsche.solarreader.backend.provider.AbstractModbusProvider;
import de.schnippsche.solarreader.backend.provider.ProviderProperty;
import de.schnippsche.solarreader.backend.table.Table;
import de.schnippsche.solarreader.backend.util.*;
import de.schnippsche.solarreader.database.Activity;
import de.schnippsche.solarreader.frontend.ui.*;
import java.io.IOException;
import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.tinylog.Logger;

/**
 * Represents a specific Modbus provider implementation for Peacefair energy meter. This class
 * extends {@link AbstractModbusProvider} and provides functionality for interacting with Peacefair
 * energy meter using the Modbus protocol.
 */
public class Peacefair extends AbstractModbusProvider {

  private static final int RETRY_COUNT = 3;
  private final NumericHelper numericHelper;

  /**
   * Constructs a new instance of the {@link Peacefair} class using the default Modbus connection
   * factory. This constructor initializes the connection to the Peacefair energy meter using the
   * default Modbus connection configuration, allowing the Peacefair class to interact with the
   * energy meter for data retrieval. It sets default values for locale, and initializes the
   * resource bundle for the plugin.
   */
  public Peacefair() {
    this(new ModbusConnectionFactory());
  }

  /**
   * Constructs a new instance of the {@link Peacefair} class with a custom {@link
   * ConnectionFactory} for managing Modbus connections. This constructor provides flexibility by
   * allowing a custom Modbus connection factory, which can be useful when specific configurations
   * for Modbus communication are needed (e.g., custom timeouts, connection pooling, or specific
   * communication settings).
   *
   * @param connectionFactory the {@link ConnectionFactory} to use for creating Modbus connections
   */
  public Peacefair(ConnectionFactory<ModbusConnection> connectionFactory) {
    super(connectionFactory);
    this.setCurrentLocale(Locale.getDefault());
    this.numericHelper = new NumericHelper();
    resourceBundle = getPluginResourceBundle();
    Logger.debug("instantiate {}", this.getClass().getName());
  }

  @Override
  public ResourceBundle getPluginResourceBundle() {
    return ResourceBundle.getBundle("peacefair", locale);
  }

  @Override
  public Activity getDefaultActivity() {
    return new Activity(TimeEvent.SUNRISE, -60, TimeEvent.SUNSET, 3600, 60, TimeUnit.SECONDS);
  }

  @Override
  public Optional<UIList> getProviderDialog() {
    UIList uiList = new UIList();
    uiList.addElement(
        new UIInputElementBuilder()
            .withId("id-address")
            .withRequired(true)
            .withType(HtmlInputType.TEXT)
            .withColumnWidth(HtmlWidth.HALF)
            .withLabel(resourceBundle.getString("peacefair.address.text"))
            .withName(Setting.PROVIDER_ADDRESS)
            .withPlaceholder(resourceBundle.getString("peacefair.address.text"))
            .withTooltip(resourceBundle.getString("peacefair.address.tooltip"))
            .withInvalidFeedback(resourceBundle.getString("peacefair.address.error"))
            .build());

    return Optional.of(uiList);
  }

  @Override
  public Optional<List<ProviderProperty>> getSupportedProperties() {
    return getSupportedPropertiesFromFile("peacefair_fields.json");
  }

  @Override
  public Optional<List<Table>> getDefaultTables() {
    return getDefaultTablesFromFile("peacefair_tables.json");
  }

  @Override
  public Setting getDefaultProviderSetting() {
    return new ModbusConfigurationBuilder()
        .withBaudrate(9600)
        .withRtuEncoding()
        .withStopBits(SerialPortStopBits.TWO)
        .withProviderAddress(1)
        .build();
  }

  @Override
  public String testProviderConnection(Setting testSetting) throws IOException {
    try (ModbusConnection testConnection = connectionFactory.createConnection(testSetting)) {
      testConnection.connect();
      testConnection.readRegister(ModbusRegisterType.INPUT_REGISTER, null, 0, 8);
      return resourceBundle.getString("peacefair.connection.successful");
    } catch (ConnectException e) {
      Logger.error(e.getMessage());
      throw new IOException(resourceBundle.getString("peacefair.connection.error"));
    }
  }

  @Override
  public void doOnFirstRun() {
    doStandardFirstRun();
  }

  @Override
  public boolean doActivityWork(Map<String, Object> variables)
      throws IOException, InterruptedException {
    try (ModbusConnection modbusConnection = getConnection()) {
      modbusConnection.connect();
      doStandardActivity(modbusConnection, variables);
      return true;
    }
  }

  @Override
  public List<Command> getAvailableCommands() {
    List<Command> availableCommands = new ArrayList<>();
    List<ValueText> optionList = List.of(new ValueText("66", "peacefair.reset.action"));
    Command command = new Command("Peacefair", "peacefair.reset", optionList, 66);
    availableCommands.add(command);
    return availableCommands;
  }

  @Override
  public void sendCommand(SendCommand sendCommand) {
    String send = sendCommand.getSend();
    Setting tmpSetting = providerData.getSetting();
    if ("66".equals(send)) {
      sendReset(tmpSetting);
    } else {
      Logger.warn("no valid command: {0}", send);
    }
  }

  /**
   * send reset to device; this is not a modbus command, so we must use as simple serial connection!
   * see <a href="https://en.peacefair.cn/product/786.html">Peacefair description</a>
   *
   * @param setting the Settings for the connection
   */
  private void sendReset(Setting setting) {
    byte[] message = new byte[] {(byte) setting.getProviderAddress(), 0x42};
    byte[] crc = calculateCRC(message);
    byte[] fullMessage = new byte[message.length + crc.length];
    System.arraycopy(message, 0, fullMessage, 0, message.length);
    System.arraycopy(crc, 0, fullMessage, message.length, crc.length);
    Logger.info(
        "send command 'reset' {}", numericHelper.byteArrayToHexStringWithChars(fullMessage));
    try (SerialUsbConnection sendConnection = new SerialUsbConnection(setting)) {
      sendConnection.connect();
      SerialPort serialPort = sendConnection.getCurrentSerialPort();
      Logger.debug("serial port:{}", serialPort);
      boolean success = false;
      for (int attempt = 0; attempt < RETRY_COUNT && !success; attempt++) {
        if (attempt > 0) {
          Thread.sleep(500);
        }
        success = sendAndCheckResponse(serialPort, fullMessage);
        Logger.debug("Attempt {} - Success: {}", attempt + 1, success);
      }
      if (success) {
        Logger.info("Command 'reset' successful");
      } else {
        Logger.error("Command 'reset' failed for serial port: {}", serialPort);
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Logger.error("Interrupted during sleep: {}", e.getMessage(), e);
    } catch (Exception e) {
      Logger.error("Cannot send reset command, reason: {0}", e.getMessage(), e);
    }
  }

  private boolean sendAndCheckResponse(SerialPort serialPort, byte[] fullMessage) {
    byte[] received = new byte[4];
    Logger.debug(
        "Sending reset message {}...", numericHelper.byteArrayToHexStringWithChars(fullMessage));
    serialPort.writeBytes(fullMessage, fullMessage.length);
    Logger.debug("Waiting for response...");
    serialPort.readBytes(received, received.length);
    Logger.debug("Received response: {}", numericHelper.byteArrayToHexStringWithChars(received));
    return (Arrays.equals(received, fullMessage));
  }

  private byte[] calculateCRC(byte[] data) {
    int crc = 0xFFFF;
    for (byte b : data) {
      crc ^= b & 0xFF;
      for (int i = 8; i != 0; i--) {
        if ((crc & 0x0001) != 0) {
          crc >>= 1;
          crc ^= 0xA001;
        } else {
          crc >>= 1;
        }
      }
    }
    // CRC wird als 2 Byte (High und Low) zurückgegeben
    return new byte[] {(byte) (crc & 0xFF), (byte) ((crc >> 8) & 0xFF)};
  }
}
