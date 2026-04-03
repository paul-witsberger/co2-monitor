// Paul Witsberger
// May 30, 2025
// Much of this code is based on the example Sensirion arduino-i2c-scd4x script: 
//  https://github.com/Sensirion/arduino-i2c-scd4x/blob/master/examples/exampleUsage/exampleUsage.ino

// The Arduino library contains functions for error handling
#include <Arduino.h>

// The ArduinoBLE library contains functions to communicate over Bluetooth
#include <ArduinoBLE.h>

// The Wire library contains functions to enable I2C
#include <Wire.h>

// The Sensirion library contains functions to communicate with the SCD-41:
//  https://github.com/Sensirion/arduino-i2c-scd4x
#include <SensirionI2cScd4x.h>

// The default address for the Adafruit SCD-41 is 0x62
#define SCD41_ADDRESS 0x62

// Initialize the variables used for taking the readings
uint16_t readingCO2;
float readingTemp;
float readingHumid;
bool dataReady;

// Initialize the variables used for performing the moving average calculations
const byte windowSize = 5;    // Number of readings in the moving average
static byte indexCO2 = 0;     // Current index for CO2
static byte indexTemp = 0;    // Current index for temperature
static byte indexHumid = 0;   // Current index for humidity
static byte valuesRead = 0;   // Number of values read so far (mod windowSize)
static uint16_t sumCO2 = 0;   // Rolling sum for CO2
static float sumTemp = 0.0;   // Rolling sum for temperature
static float sumHumid = 0.0;  // Rolling sum for humidity
static uint16_t valuesCO2[windowSize]; // Array of all values in the window for CO2
static float valuesTemp[windowSize];   // Array of all values in the window for temperature
static float valuesHumid[windowSize];  // Array of all values in the window for humidity
float avgCO2 = 0.0;           // Moving average for CO2
float avgTemp = 0.0;          // Moving average for temperature
float avgHumid = 0.0;         // Moving average for humidity

// Initialize the sensor object
SensirionI2cScd4x sensor;

// macro definitions
// make sure that we use the proper definition of NO_ERROR
#ifdef NO_ERROR
#undef NO_ERROR
#endif
#define NO_ERROR 0
static char errorMessage[64];
static int16_t err;

// Define UUIDs - some are defined by the Bluetooth SIG and are called "adopted UUIDs"
// CO2 sensors do not have a 16-bit UUID assigned, so a custom service and characteristic are needed
const char* co2ServiceUUID = "3e6cebcd-d4f8-46e2-9513-056d94a6377c";
const char* co2CharacteristicUUID = "b70c91c7-40b6-461f-aeff-4b15a16fd0e7";
// Environmental Sensing Service (ESS) includes temperature and humidity
const char* essServiceUUID = "181A";
const char* temperatureCharacteristicUUID = "2A6E";
const char* humidityCharacteristicUUID = "2A6F";
// Device Information Service (DIS) provides basic information about the device
const char* disServiceUUID = "180A";
const char* manufacturerNameCharacteristicUUID = "2A29";
const char* modelNumberCharacteristicUUID = "2A24";
// Battery Service (BAS) provides the battery level
const char* basServiceUUID = "180F";
const char* batteryLevelCharacteristicUUID = "2A19";

// Create the service and characteristic objects
BLEService co2Service(co2ServiceUUID);
BLEUnsignedIntCharacteristic co2Characteristic(co2CharacteristicUUID, BLERead | BLENotify | BLEIndicate);
BLEService essService(essServiceUUID);
BLEIntCharacteristic temperatureCharacteristic(temperatureCharacteristicUUID, BLERead | BLENotify);
BLEUnsignedIntCharacteristic humidityCharacteristic(humidityCharacteristicUUID, BLERead | BLENotify);
BLEService disService(disServiceUUID);
BLEByteCharacteristic manufacturerNameCharacteristic(manufacturerNameCharacteristicUUID, BLERead);
BLEByteCharacteristic modelNumberCharacteristic(modelNumberCharacteristicUUID, BLERead);
BLEService basService(basServiceUUID);
BLEByteCharacteristic batteryLevelCharacteristic(batteryLevelCharacteristicUUID, BLERead | BLENotify);

// Create descriptors for the characteristics
BLEDescriptor co2Descriptor(co2CharacteristicUUID, "CO2 concentration [ppm]");
BLEDescriptor temperatureDescriptor(temperatureCharacteristicUUID, "Temperature [C]");
BLEDescriptor humidityDescriptor(humidityCharacteristicUUID, "Relative humidity [%]");
BLEDescriptor manufacturerNameDescriptor(manufacturerNameCharacteristicUUID, "Manufacturer name");
BLEDescriptor modelNumberDescriptor(modelNumberCharacteristicUUID, "Model number");
BLEDescriptor batteryLevelDescriptor(batteryLevelCharacteristicUUID, "Battery level");

// BLE event handlers for when a central device connects and disconnects
void bleConnectHandler(BLEDevice central);
void bleDisconnectHandler(BLEDevice central);

// Test function
void PrintUint64(uint64_t& value) {
    Serial.print("0x");
    Serial.print((uint32_t)(value >> 32), HEX);
    Serial.print((uint32_t)(value & 0xFFFFFFFF), HEX);
}

// Moving average formula adapted from:
//   https://stackoverflow.com/a/67213258
void movingAverage(float valueCO2, float valueTemp, float valueHumid) {
  // Add the new values to the sums
  sumCO2 += valueCO2;
  sumTemp += valueTemp;
  sumHumid += valueHumid;

  // If the window is full, adjust the sum by deleting the oldest value
  if (valuesRead == windowSize) {
    sumCO2 -= valuesCO2[indexCO2];
    sumTemp -= valuesTemp[indexTemp];
    sumHumid -= valuesHumid[indexHumid];
  }

  // Replace the oldest values with the new values
  valuesCO2[indexCO2] = valueCO2;
  valuesTemp[indexTemp] = valueTemp;
  valuesHumid[indexHumid] = valueHumid;

  // If the index reaches windowSize, reset it to zero
  indexCO2++;
  indexTemp++;
  indexHumid++;
  if (indexCO2 >= windowSize) {
    indexCO2 = 0;
    indexTemp = 0;
    indexHumid = 0;
  }

  // If the number of readings has not yet reached windowSize, increment valuesRead
  if (valuesRead < windowSize) {
    valuesRead += 1;
  }

  // Compute the new moving averages
  avgCO2 = sumCO2 / valuesRead;
  avgTemp = sumTemp / valuesRead;
  avgHumid = sumHumid / valuesRead;
}

void bleConnectHandler(BLEDevice central) {
    Serial.print("Connected event, central: ");
    Serial.println(central.address());
}

// IMPORTANT: Restart advertising after a disconnection
void bleDisconnectHandler(BLEDevice central) {
    Serial.print("Disconnected event, central: ");
    Serial.println(central.address());
    Serial.println("Restarting advertising...");
    BLE.advertise();
}

void setup() {
  // Begin Serial communication (if possible)
  Serial.begin(9600);
  unsigned long startTime = millis();
  while (!Serial && millis() - startTime < 500) {
    delay(100);
  }

  // Begin I2C communication with the sensor
  Wire.begin();
  sensor.begin(Wire, SCD41_ADDRESS);
  delay(30);

  // Ensure that sensor is in a clean state
  err = sensor.wakeUp();
  if (err != NO_ERROR) {
    Serial.print("Error trying to execute wakeUp(): ");
    errorToString(err, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
  }
  // Stop periodic measurements
  err = sensor.stopPeriodicMeasurement();
  if (err != NO_ERROR) {
      Serial.print("Error trying to execute stopPeriodicMeasurement(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
  }
  // Restart sensor
  err = sensor.reinit();
  if (err != NO_ERROR) {
      Serial.print("Error trying to execute reinit(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
  }
  // Read out information about the sensor (this helps test that the sensor is online)
  uint64_t serialNumber = 0;
  err = sensor.getSerialNumber(serialNumber);
  if (err != NO_ERROR) {
      Serial.print("Error trying to execute getSerialNumber(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
      return;
  }
  // Restart periodic measurements
  err = sensor.startPeriodicMeasurement();
  if (err != NO_ERROR) {
      Serial.print("Error trying to execute startPeriodicMeasurement(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
      return;
  }
  // Start the Bluetooth LE module
  if (!BLE.begin()) {
    Serial.println("Error starting the Bluetooth module");
  }

  // Assign the characteristics to the appropriate services
  co2Service.addCharacteristic(co2Characteristic);
  essService.addCharacteristic(temperatureCharacteristic);
  essService.addCharacteristic(humidityCharacteristic);
  disService.addCharacteristic(manufacturerNameCharacteristic);
  disService.addCharacteristic(modelNumberCharacteristic);
  basService.addCharacteristic(batteryLevelCharacteristic);

  // Start advertising the BLE connection
  BLE.setLocalName("CO2 Monitor");
  BLE.addService(co2Service);
  BLE.addService(essService);
  BLE.addService(disService);
  BLE.addService(basService);
  BLE.setAdvertisedService(co2Service);

  // Set up event handlers
   BLE.setEventHandler(BLEConnected, bleConnectHandler);
   BLE.setEventHandler(BLEDisconnected, bleDisconnectHandler);
  // co2Characteristic.setEventHandler(BLEWritten, co2CharacteristicWritten);

  BLE.advertise();

  Serial.println();
  Serial.println("Avg CO2 [ppm], Curr CO2 [ppm], Avg Temp [F], Curr Temp [F], Avg RelHum [%], Curr RelHum [%]");
  Serial.println();

}

void loop() {
  // Handle outstanding BLE tasks
  BLE.poll();

  // Connect to central device
  BLEDevice central = BLE.central();
  // Serial.println("Discovering central device...");
  
  if (central && central.connected()) {
    // Serial.println("Connected!");
    dataReady = false;
    err = sensor.getDataReadyStatus(dataReady);

    if (err == NO_ERROR && dataReady) {
      // Declare array to hold sensor readings
      const size_t readingsArrSize = 6;
      float readingsArr[readingsArrSize];

      // Collect readings from the sensor
      getReadings(readingsArr, readingsArrSize);
      
      // Send the readings to the central device via BLE
      transmitReadings(readingsArr, readingsArrSize);
    }
  }
}

void getReadings(float *readingsArr, size_t numElements) {
  dataReady = false;

  // Check if there is data ready
  err = sensor.getDataReadyStatus(dataReady);
  if (err != NO_ERROR) {
    Serial.print("Error trying to execute getDataReadyStatus(): ");
    errorToString(err, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
    return;
  }

  // If the data is not ready, sample at every 100ms (10 Hz) until it is ready
  while (!dataReady) {
    delay(100);
    err = sensor.getDataReadyStatus(dataReady);
    if (err != NO_ERROR) {
      Serial.print("Error trying to execute getDataReadyStatus(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
      return;
    }
  }

  // Get readings from the sensor
  err = sensor.readMeasurement(readingCO2, readingTemp, readingHumid);

  // Check that readings were received
  if (err != NO_ERROR) {
      Serial.print("Error trying to execute readMeasurement(): ");
      errorToString(err, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
      return;
  } else {
    movingAverage(readingCO2, readingTemp, readingHumid);
  }

  // Print the information
  Serial.print((uint16_t)avgCO2);
  Serial.print(" , ");
  Serial.print(readingCO2);
  Serial.print(" , ");
  Serial.print(avgTemp * 9 / 5 + 32);
  Serial.print(" , ");
  Serial.print(readingTemp * 9 / 5 + 32);
  Serial.print(" , ");
  Serial.print(avgHumid);
  Serial.print(" , ");
  Serial.print(readingHumid);
  Serial.println();

  // Store the readings
  readingsArr[0] = readingCO2;
  readingsArr[1] = readingTemp;
  readingsArr[2] = readingHumid;
  readingsArr[3] = avgCO2;
  readingsArr[4] = avgTemp;
  readingsArr[5] = avgHumid;
}

// Write new values for CO2, temperature, and humidity; these will all notify the central device
void transmitReadings(float *readingsArr, size_t numElements) {
  co2Characteristic.writeValue((uint16_t)readingsArr[0]);
  temperatureCharacteristic.writeValue((int16_t)(readingsArr[1] * 100.0));
  humidityCharacteristic.writeValue((uint16_t)(readingsArr[2] * 100.0));
}
