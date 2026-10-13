/*
  Requisitos 
  RF01  Informacoes no celular 
  RF02  Qualidade da agua ................. pH, TDS e temperatura
  RF03  Grandezas elevadas ou nao ......... campos *_status (OK / ALTO / BAIXO)
  RF05/06 Relatarios e historico .......... o ESP32 envia os dados com tempo 
                                            o app e quem guarda o historico e gera os relatorios
  RF04  Estimar a conta ................... o app calcula, usando consumo_dia_l / vazao_total_l
  RNF01 Nao alterar a agua ................ sensor TDS so e energizado durante a leitura
  RNF04 Precisao .......................... media/mediana de amostras, compensacao de temperatura,
                                            alerta de sensor com defeito (autodiagnostico)
*/

#include "BluetoothSerial.h"
#include <OneWire.h>
#include <DallasTemperature.h>

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth classico precisa estar habilitado. Va em "Tools > Partition Scheme" e confirme que o Bluetooth esta ativo para essa placa.
#endif

BluetoothSerial SerialBT;
const char* NOME_BLUETOOTH = "WaterTank_ESP32"; // nome que vai aparecer na lista de pareamento

// ---------- PINOS ----------
#define PIN_PH      34            // Entrada analógica  - PH4502C
#define PIN_TDS     45            // Entrada analógica  - TDS Sensor
#define PIN_DS18B20 4         // Pino digital - DATA do DS18B20 (com pull-up de 4.7k)
#define PIN_VAZAO         
#define PIN_VOLUME

// ---------- LIMITES DE QUALIDADE ----------
const float PH_MIN = 6.0;
const float PH_MAX = 9.0;
const float TDS_MAX_PPM = 500.0;

// ---------- ALERTAS ----------


// ----------- FALHAS -----------


// ---------- VARIÁVEIS GLOBAIS ----------
float valorPH = 0;

float valorTDS = 0;            // ppm
float valorTemperatura = 25;   // °C (comeca em 25 para a compensacao do TDS)
float volumeCaixaL;            // volume atual de agua dentro da caixa (litros)
float vazaoLMin = 0;           // vazao atual (L/min)
float volumeTotalL = 0;        // total de litros que passaram pelo sensor de vazao
float consumoDiaL = 0;         // consumo estimado nas ultimas 24 h de funcionamento

// Temperatura DS18B20
OneWire oneWire(PIN_DS18B20);
DallasTemperature sensorTemp(&oneWire);

unsigned long ultimaLeitura = 0;

// ----------- FUNCOES AUXILIARES ----------
float lerTensaoMedia(int pino) {
    const int AMOSTRAS = 15;
    unsigned long soma = 0;

    for (int i = 0; i < AMOSTRAS; i++) {
        soma += analogReadMilliVolts(pino);
        delay(5);
    }

    return (soma / (float)AMOSTRAS) / 1000.0;
}

// ---------- LEITURA DOS SENSORES ----------

// Substituir pelo fator calculado com os resistores reais
const float FATOR_DIVISOR_PH = 1.667; // valor com 1 resistor 10 kΩ entre a saida do modulo e o pino do ESP32 e 20 kΩ entre o pino do ESP32 e o GND

void lerPH(){
  // Tensao medida pelo ESP32
  float tensaoPino = lerTensaoMedia(PIN_PH);

  // Estima a tensao original na saida do PH-4502C
  float tensao = tensaoPino * FATOR_DIVISOR_PH;

  // Estimativa inicial- calibrar com solucoes
  float ph = 7.0 + ((2.5 - tensao) / 0.18);

  // Nao substitui o ultimo valor valido por uma leitura invalida
  if (tensaoPino < 0.05 || tensaoPino > 3.1 || ph < 0.0 || ph > 14.0){
      return;
  }

  valorPH = ph;
}

void lerTDS(){
  //Alimenta o sensor somente durante a leitura
  digitalWrite(PIN_TDS_PWR, HIGH);
  delay(100);

  float tensao = lerTensaoMedia(PIN_TDS);

  digitalWrite(PIN_TDS_PWR, LOW);

  // Ignora leituras fora da faixa esperada
  if (tensao < 0.005 || tensao > 3.2) {
      return;
  }

  // Compensacao de temperatura para referencia de 25 °C
  float coeficiente = 1.0 + 0.02 * (valorTemperatura - 25.0);

  if (coeficiente <= 0.0) {
      return;
  }

  float tensaoComp = tensao / coeficiente;

  // Conversao aproximada para ppm
  float tds = (133.42 * tensaoComp * tensaoComp * tensaoComp - 255.86 * tensaoComp * tensaoComp + 857.39 * tensaoComp) * 0.5;

  if (tds < 0.0) {
      tds = 0.0;
  }

  valorTDS = tds;
}

void calcularVazao(){
  
}

void lerTemperatura(){
  sensorTemp.requestTemperatures();

  float t = sensorTemp.getTempCByIndex(0);

  // Ignora leituras invalidas ou fora da faixa adotada
  if (t == DEVICE_DISCONNECTED_C || t < -10.0 || t > 85.0) {
      return;
  }

  valorTemperatura = t;
}

void medirNivelAgua(){
  
}

// ---------- CONSUMO ----------
void analisarConsumo(){
  
}

// ---------- STATUS ----------
const char* statusPH(){
  if (valorPH < PH_MIN) return "BAIXO";
  if (valorPH > PH_MAX) return "ALTO";

  return "OK";
}

const char* statusTDS(){
  if (valorTDS > TDS_MAX_PPM) return "ALTO";

  return "OK";
}



// ---------- ARMAZENAMENTO LOCAL ----------


// ---------- ENVIO VIA BLUETOOTH ----------
void enviarDadosBluetooth(){
 
}

// ---------- SETUP ----------
void setup(){
  Serial.begin(115200);

  // Pinos
  pinMode(PIN_PH, INPUT);
  pinMode(PIN_TDS, INPUT);

  // Sensor de temperatura
  sensorTemp.begin();

  // Bluetooth
  SerialBT.begin(NOME_BLUETOOTH);
  SerialBT.setTimeout(50);
  Serial.println("Bluetooth iniciado. Procure por '" + String(NOME_BLUETOOTH) + "' no celular pra parear.");
}

// ---------- LOOP ----------
void loop(){
  lerPH();
  lerTemperatura();
  lerTDS();
  medirNivelAgua();
}
