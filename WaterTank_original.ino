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
#include <Preferences.h>
#include <ThreeWire.h>   
#include <RtcDS1302.h>

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth classico precisa estar habilitado. Va em "Tools > Partition Scheme" e confirme que o Bluetooth esta ativo para essa placa.
#endif

BluetoothSerial SerialBT;
const char* NOME_BLUETOOTH = "WaterTank_ESP32"; // nome que vai aparecer na lista de pareamento

// ------------------ PINOS  -----------------
#define PIN_PH      34   // Entrada analogica (ADC1) - PH4502C (pino so de entrada)
#define PIN_TDS     35   // Entrada analogica (ADC1) - TDS Sensor (pino so de entrada)
#define PIN_TDS_PWR 25   // Saida digital - alimenta o sensor TDS so durante a leitura
#define PIN_DS18B20 4    // Pino digital - DATA do DS18B20 (com pull-up de 4.7k)
#define PIN_VAZAO   27   // Entrada digital com interrupcao - sensor de vazao
#define PIN_VOLUME  
#define PIN_RTC_CLK 18   // DS1302 - CLK (SCLK)
#define PIN_RTC_DAT 19   // DS1302 - DAT (I/O)
#define PIN_RTC_RST 23   // DS1302 - RST (CE)

// ---------- LIMITES DE QUALIDADE ----------
const float PH_MIN = 6.0;
const float PH_MAX = 9.0;
const float TDS_MAX_PPM = 500.0;

// ---------- TEMPO ----------
const unsigned long INTERVALO_LEITURA_MS = 2000; // intervalo entre ciclos de leitura dos sensores lentos
const unsigned long INTERVALO_VAZAO_MS = 1000;   // calculo da vazao a cada 1 s

// ---------- SENSOR DE VAZAO YF-S201 ----------
// Frequencia (Hz) = 7,5 * vazao (L/min)  ->  ~450 pulsos por litro
const float PULSOS_POR_LITRO = 450.0;
const float FATOR_VAZAO = 7.5;

volatile unsigned long contadorPulsos = 0;
portMUX_TYPE muxVazao = portMUX_INITIALIZER_UNLOCKED;
unsigned long ultimoCalculoVazao = 0;

void IRAM_ATTR contarPulso() {
    portENTER_CRITICAL_ISR(&muxVazao);
    contadorPulsos++;
    portEXIT_CRITICAL_ISR(&muxVazao);
}

// ---------- RELOGIO (RTC DS1302) ----------
ThreeWire fioRTC(PIN_RTC_DAT, PIN_RTC_CLK, PIN_RTC_RST); // ordem: DAT (I/O), CLK (SCLK), RST (CE)
RtcDS1302<ThreeWire> Rtc(fioRTC);

const int ANO_MINIMO = 2025;   // data anterior a isso e tratada como relogio nao ajustado
bool relogioValido = false;    // so fecha dia/mes quando o relogio tem data confiavel

// ---------- ALERTAS ----------
// Alertas de QUALIDADE DA AGUA (sensor funcionando, mas valor fora do limite)
const uint8_t ALERTA_PH  = 1 << 0;   // pH abaixo do minimo ou acima do maximo
const uint8_t ALERTA_TDS = 1 << 1;   // TDS acima do maximo
uint8_t alertas = 0;                 // bits de ALERTA_* ativos

// ----------- FALHAS -----------
// Falhas de SENSOR (autodiagnostico, RNF04): o valor lido nao e confiavel
const uint8_t FALHA_PH   = 1 << 0;
const uint8_t FALHA_TDS  = 1 << 1;
const uint8_t FALHA_TEMP = 1 << 2;
const uint8_t FALHA_RTC  = 1 << 3;
uint8_t falhas = 0;                  // bits de FALHA_* ativos

// Quantas leituras invalidas SEGUIDAS confirmam a falha (evita alarme por um ruido isolado)
const int LIMITE_FALHAS_CONSECUTIVAS = 3;
int contFalhaPH = 0;
int contFalhaTDS = 0;
int contFalhaTemp = 0;

// Atualiza o contador do sensor e liga/desliga o bit de falha.
// A falha some quando uma leitura valida volta.
void registrarLeitura(uint8_t bit, int &contador, bool leituraValida, const char* nome){
  if (leituraValida) {
      contador = 0;

      if (falhas & bit) {
          falhas &= ~bit;
          Serial.printf("Sensor %s voltou ao normal.\n", nome);
      }
      return;
  }

  if (contador < LIMITE_FALHAS_CONSECUTIVAS) {
      contador++;
  }

  if (contador >= LIMITE_FALHAS_CONSECUTIVAS && !(falhas & bit)) {
      falhas |= bit;
      Serial.printf("FALHA no sensor %s: leituras invalidas seguidas.\n", nome);
  }
}

// ---------- VARIAVEIS GLOBAIS ----------
float valorPH =             0;           
float valorTDS =            0;            
float valorTemperatura =   25;  // °C (comeca em 25 para a compensacao do TDS)
float volumeCaixaL =        0;  // volume atual de agua dentro da caixa (litros)
float vazaoLMin =           0;  // vazao atual (L/min)
float volumeTotalL =        0;  // litros que passaram pelo sensor de vazao no mes atual (zera a cada mes)
float volumeMesAnteriorL =  0;  // total de litros do ultimo mes fechado
float consumoDiaL =         0;  // consumo acumulado no dia atual (pela data do RTC)
float consumoDiaAnteriorL = 0;  // consumo do ultimo dia fechado

// Estimativa da conta de agua
float tarifaReaisPorM3 =      0;  // AJUSTAR: tarifa da concessionaria em R$ por m3 (1 m3 = 1000 L)
float contaEstimadaReais =    0;  // valor aproximado da conta do mes atual (R$)
float contaMesAnteriorReais = 0;  // conta estimada do ultimo mes fechado (R$)

// ---------- CONTROLE DE PERIODOS / ARMAZENAMENTO ----------
const unsigned long INTERVALO_SALVAR_MS = 10UL * 60UL * 1000UL;  // grava na flash no maximo a cada 10 min

Preferences prefs;
uint32_t chaveDiaAtual =          0;  // dia em curso no formato AAAAMMDD (0 = ainda nao definido)
uint32_t chaveMesAtual =          0;  // mes em curso no formato AAAAMM
unsigned long ultimoSalvamento =  0;  // ultima gravacao na flash
float volumeTotalSalvoL =        -1;  // volume total da ultima gravacao (evita gravar sem mudanca)

// Temperatura DS18B20
OneWire oneWire(PIN_DS18B20);
DallasTemperature sensorTemp(&oneWire);

unsigned long ultimaLeitura = 0;

// ----------- FUNCOES AUXILIARES ----------
// Mediana de 15 amostras: ignora picos isolados melhor que a media (RNF04)
float lerTensaoMediana(int pino) {
    const int AMOSTRAS = 15;
    int amostras[AMOSTRAS];

    for (int i = 0; i < AMOSTRAS; i++) {
        amostras[i] = analogReadMilliVolts(pino);
        delay(5);
    }

    // Ordenacao por insercao (vetor pequeno)
    for (int i = 1; i < AMOSTRAS; i++) {
        int chave = amostras[i];
        int j = i - 1;

        while (j >= 0 && amostras[j] > chave) {
            amostras[j + 1] = amostras[j];
            j--;
        }
        amostras[j + 1] = chave;
    }

    return amostras[AMOSTRAS / 2] / 1000.0;   // valor do meio, em volts
}

// ---------- LEITURA DOS SENSORES ----------

// Divisor de tensao do pH: 10 kΩ em serie com a saida do modulo e 20 kΩ do pino do ESP32 ao GND.
// O pino recebe 20/(10+20) = 2/3 da tensao do modulo, entao o fator e 30/20 = 1,5.
// Se mudar os resistores: FATOR = (R1 + R2) / R2
const float FATOR_DIVISOR_PH = 1.5;

void lerPH(){
  // Tensao medida pelo ESP32
  float tensaoPino = lerTensaoMediana(PIN_PH);

  // Estima a tensao original na saida do PH-4502C
  float tensao = tensaoPino * FATOR_DIVISOR_PH;

  // Estimativa inicial- calibrar com solucoes
  float ph = 7.0 + ((2.5 - tensao) / 0.18);

  // Tensao no limite do ADC (sensor desconectado/em curto) ou pH impossivel = leitura invalida
  bool leituraValida = !(tensaoPino < 0.05 || tensaoPino > 3.1 || ph < 0.0 || ph > 14.0);

  registrarLeitura(FALHA_PH, contFalhaPH, leituraValida, "pH");

  // Nao substitui o ultimo valor valido por uma leitura invalida
  if (!leituraValida){
      return;
  }

  valorPH = ph;
}

void lerTDS(){
  //Alimenta o sensor somente durante a leitura
  digitalWrite(PIN_TDS_PWR, HIGH);
  delay(100);

  float tensao = lerTensaoMediana(PIN_TDS);

  digitalWrite(PIN_TDS_PWR, LOW);

  // Tensao praticamente zero (sonda desconectada) ou no limite do ADC = leitura invalida
  bool leituraValida = (tensao >= 0.005 && tensao <= 3.2);

  registrarLeitura(FALHA_TDS, contFalhaTDS, leituraValida, "TDS");

  // Ignora leituras fora da faixa esperada
  if (!leituraValida) {
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
  unsigned long agora = millis();
  float segundos = (agora - ultimoCalculoVazao) / 1000.0;

  if (segundos <= 0.0) {
      return;
  }

  // Le e zera o contador sem risco de a interrupcao mexer nele no meio
  portENTER_CRITICAL(&muxVazao);
  unsigned long pulsos = contadorPulsos;
  contadorPulsos = 0;
  portEXIT_CRITICAL(&muxVazao);

  ultimoCalculoVazao = agora;

  // Vazao instantanea: Q (L/min) = (pulsos / segundos) / 7,5
  vazaoLMin = (pulsos / segundos) / FATOR_VAZAO;

  // Litros deste ciclo: litros = pulsos / 450
  float litros = pulsos / PULSOS_POR_LITRO;

  volumeTotalL += litros;   // total do mes atual
  consumoDiaL  += litros;   // total do dia atual
}

void lerTemperatura(){
  sensorTemp.requestTemperatures();

  float t = sensorTemp.getTempCByIndex(0);

  // Desconectado, fora da faixa ou exatamente 85 °C (valor de reset do DS18B20 quando a conversao falha)
  bool leituraValida = !(t == DEVICE_DISCONNECTED_C || t < -10.0 || t >= 85.0);

  registrarLeitura(FALHA_TEMP, contFalhaTemp, leituraValida, "temperatura");

  // Ignora leituras invalidas ou fora da faixa adotada
  if (!leituraValida) {
      return;
  }

  valorTemperatura = t;
}

void medirNivelAgua(){
  
}

// ---------- RELOGIO ----------
// Confere se o DS1302 esta funcionando e com uma data plausivel
bool verificarRelogio(){
  if (!Rtc.GetIsRunning() || !Rtc.IsDateTimeValid()) {
      relogioValido = false;
  } else {
      RtcDateTime agora = Rtc.GetDateTime();
      relogioValido = (agora.Year() >= ANO_MINIMO);
  }

  if (relogioValido) {
      falhas &= ~FALHA_RTC;
  } else {
      falhas |= FALHA_RTC;
  }

  return relogioValido;
}

// ---------- CONSUMO ----------
// Estima o valor da conta a partir do volume do mes e da tarifa (RF04)
void analisarConsumo(){
  float volumeM3 = volumeTotalL / 1000.0;   // litros -> metros cubicos

  contaEstimadaReais = volumeM3 * tarifaReaisPorM3;
}

// Fecha o dia e o mes quando a data do RTC muda, e grava o consumo na flash
void atualizarPeriodos(){
  if (!verificarRelogio()) {
      return;   // sem data confiavel nao fecha nada
  }

  RtcDateTime agora = Rtc.GetDateTime();
  uint32_t chaveDia = agora.Year() * 10000UL + agora.Month() * 100UL + agora.Day();
  uint32_t chaveMes = agora.Year() * 100UL + agora.Month();

  // Primeira vez com relogio valido: so marca o periodo atual
  if (chaveDiaAtual == 0) {
      chaveDiaAtual = chaveDia;
      chaveMesAtual = chaveMes;
      salvarArmazenamento();
      return;
  }

  // Data anterior a ultima gravada (relogio voltou): nao fecha nada
  if (chaveDia < chaveDiaAtual) {
      return;
  }

  bool fechou = false;

  // Virou o mes: guarda o total do mes e zera o volume
  if (chaveMes > chaveMesAtual) {
      volumeMesAnteriorL = volumeTotalL;
      contaMesAnteriorReais = (volumeTotalL / 1000.0) * tarifaReaisPorM3;
      volumeTotalL = 0;
      contaEstimadaReais = 0;
      chaveMesAtual = chaveMes;
      fechou = true;
  }

  // Virou o dia: guarda o consumo do dia e zera
  if (chaveDia > chaveDiaAtual) {
      consumoDiaAnteriorL = consumoDiaL;
      consumoDiaL = 0;
      chaveDiaAtual = chaveDia;
      fechou = true;
  }

  if (fechou) {
      salvarArmazenamento();
      return;
  }

  // Durante o dia, grava de tempos em tempos e so se o volume mudou
  if (millis() - ultimoSalvamento >= INTERVALO_SALVAR_MS && volumeTotalL != volumeTotalSalvoL) {
      salvarArmazenamento();
  }
}

// ---------- STATUS ----------
// Com o sensor em falha o valor nao e confiavel, entao o status e "FALHA" (e nao OK/ALTO/BAIXO)
const char* statusPH(){
  if (falhas & FALHA_PH) return "FALHA";
  if (valorPH < PH_MIN) return "BAIXO";
  if (valorPH > PH_MAX) return "ALTO";

  return "OK";
}

const char* statusTDS(){
  if (falhas & FALHA_TDS) return "FALHA";
  if (valorTDS > TDS_MAX_PPM) return "ALTO";

  return "OK";
}

const char* statusTemperatura(){
  if (falhas & FALHA_TEMP) return "FALHA";

  return "OK";
}

// Recalcula os alertas de qualidade da agua (so para sensores sem falha)
void atualizarAlertas(){
  alertas = 0;

  if (!(falhas & FALHA_PH) && (valorPH < PH_MIN || valorPH > PH_MAX)) {
      alertas |= ALERTA_PH;
  }

  if (!(falhas & FALHA_TDS) && valorTDS > TDS_MAX_PPM) {
      alertas |= ALERTA_TDS;
  }
}

// ---------- ARMAZENAMENTO LOCAL ----------
// Guarda os totais na flash (NVS) para nao perder o consumo se o ESP32 reiniciar ou ficar sem energia

void salvarArmazenamento(){
  prefs.putFloat("volTotal",      volumeTotalL);
  prefs.putFloat("consDia",       consumoDiaL);
  prefs.putFloat("consAnt",       consumoDiaAnteriorL);
  prefs.putUInt("chaveDia",       chaveDiaAtual);
  prefs.putUInt("chaveMes",       chaveMesAtual);
  prefs.putFloat("volMesAnt",     volumeMesAnteriorL);
  prefs.putFloat("contaMesAnt",   contaMesAnteriorReais);

  volumeTotalSalvoL = volumeTotalL;
  ultimoSalvamento = millis();
}

void carregarArmazenamento(){
  prefs.begin("watertank", false);   // false = leitura e escrita

  volumeTotalL          = prefs.getFloat( "volTotal",     0);
  consumoDiaL           = prefs.getFloat( "consDia",      0);
  consumoDiaAnteriorL   = prefs.getFloat( "consAnt",      0);
  chaveDiaAtual         = prefs.getUInt(  "chaveDia",     0);
  chaveMesAtual         = prefs.getUInt(  "chaveMes",     0);
  volumeMesAnteriorL    = prefs.getFloat( "volMesAnt",    0);
  contaMesAnteriorReais = prefs.getFloat( "contaMesAnt",  0);

  volumeTotalSalvoL = volumeTotalL;
  ultimoSalvamento = millis();
}

// ---------- COMANDOS DO APP (BLUETOOTH) ----------
// Formato esperado: SYNC;AAAA-MM-DD HH:MM:SS  (hora local do celular), terminado em quebra de linha
void lerComandosBluetooth(){
  if (!SerialBT.available()) {
      return;
  }

  String linha = SerialBT.readStringUntil('\n');
  linha.trim();

  if (!linha.startsWith("SYNC;")) {
      return;
  }

  int ano, mes, dia, hora, minuto, segundo;

  if (sscanf(linha.c_str(), "SYNC;%d-%d-%d %d:%d:%d", &ano, &mes, &dia, &hora, &minuto, &segundo) != 6) {
      SerialBT.println("ERRO;SYNC");
      return;
  }

  // Rejeita datas e horas absurdas
  if (ano < ANO_MINIMO || ano > 2099 || mes < 1 || mes > 12 || dia < 1 || dia > 31 ||
      hora < 0 || hora > 23 || minuto < 0 || minuto > 59 || segundo < 0 || segundo > 59) {
      SerialBT.println("ERRO;SYNC");
      return;
  }

  // Libera a escrita, garante que o relogio esta rodando e ajusta
  if (Rtc.GetIsWriteProtected()) {
      Rtc.SetIsWriteProtected(false);
  }
  if (!Rtc.GetIsRunning()) {
      Rtc.SetIsRunning(true);
  }
  Rtc.SetDateTime(RtcDateTime(ano, mes, dia, hora, minuto, segundo));

  if (!verificarRelogio()) {
      SerialBT.println("ERRO;SYNC");
      return;
  }

  // Se o ajuste voltou a data para antes da gravada, realinha os periodos sem fechar nada
  uint32_t novaChaveDia = ano * 10000UL + mes * 100UL + dia;
  uint32_t novaChaveMes = ano * 100UL + mes;

  if (chaveDiaAtual != 0 && novaChaveDia < chaveDiaAtual) {
      chaveDiaAtual = novaChaveDia;
      chaveMesAtual = novaChaveMes;
      salvarArmazenamento();
  }

  SerialBT.println("OK;SYNC");
}

// ---------- SAIDA NA SERIAL (DEPURACAO) ----------
// Lista as falhas ativas por nome, por exemplo "pH,TEMP"
String descreverFalhas(){
  if (falhas == 0) {
      return "nenhuma";
  }

  String s = "";
  if (falhas & FALHA_PH)   s += "pH,";
  if (falhas & FALHA_TDS)  s += "TDS,";
  if (falhas & FALHA_TEMP) s += "TEMP,";
  if (falhas & FALHA_RTC)  s += "RTC,";

  s.remove(s.length() - 1);   // tira a ultima virgula
  return s;
}

// Uma linha por ciclo: data/hora, pH, TDS, temperatura (valor + condicao), vazao e falhas
void imprimirSerial(){
  char dataHora[24];

  if (relogioValido) {
      RtcDateTime agora = Rtc.GetDateTime();
      snprintf(dataHora, sizeof(dataHora), "%04d-%02d-%02d %02d:%02d:%02d",
               agora.Year(), agora.Month(), agora.Day(),
               agora.Hour(), agora.Minute(), agora.Second());
  } else {
      strcpy(dataHora, "sem data");
  }

  Serial.printf("[%s] pH=%.2f %s | TDS=%.0f ppm %s | T=%.1f C %s | Vazao=%.2f L/min | falhas=%s\n",
                dataHora,
                valorPH, statusPH(),
                valorTDS, statusTDS(),
                valorTemperatura, statusTemperatura(),
                vazaoLMin,
                descreverFalhas().c_str());
}

// ---------- ENVIO VIA BLUETOOTH ----------
void enviarDadosBluetooth(){
 
}

// ---------- SETUP ----------
void setup(){
  Serial.begin(115200);

  // Recupera os totais gravados antes do ultimo desligamento
  carregarArmazenamento();

  // Pinos
  pinMode(PIN_PH, INPUT);
  pinMode(PIN_TDS, INPUT);
  pinMode(PIN_TDS_PWR, OUTPUT);
  digitalWrite(PIN_TDS_PWR, LOW);   // sensor TDS desligado ate a hora da leitura
  pinMode(PIN_VAZAO, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_VAZAO), contarPulso, FALLING);
  ultimoCalculoVazao = millis();
  //pinMode(PIN_VOLUME, INPUT);

  // Relogio DS1302
  Rtc.Begin();
  if (Rtc.GetIsWriteProtected()) {
      Rtc.SetIsWriteProtected(false);
  }
  if (!Rtc.GetIsRunning()) {
      Rtc.SetIsRunning(true);
  }
  if (!verificarRelogio()) {
      Serial.println("RTC sem data valida. Sincronize pelo app (SYNC).");
  }

  // Sensor de temperatura
  sensorTemp.begin();
  sensorTemp.setResolution(10);   // conversao em ~190 ms (em vez de ~750 ms), precisao de 0,25 °C

  // Bluetooth
  SerialBT.begin(NOME_BLUETOOTH);
  SerialBT.setTimeout(50);
  Serial.println("Bluetooth iniciado. Procure por '" + String(NOME_BLUETOOTH) + "' no celular pra parear.");
}

// ---------- LOOP ----------
void loop(){
  lerComandosBluetooth();

  unsigned long agora = millis();

  // Executa um ciclo a cada INTERVALO_LEITURA_MS, sem travar o loop com delay longo
  if (agora - ultimaLeitura >= INTERVALO_LEITURA_MS) {
    ultimaLeitura = agora;

    lerTemperatura();   
    lerPH();
    lerTDS();
    atualizarAlertas();
    medirNivelAgua();
    imprimirSerial();
  }

  if (millis() - ultimoCalculoVazao >= INTERVALO_VAZAO_MS) {
    calcularVazao();
    atualizarPeriodos();
    analisarConsumo();
  }
}
