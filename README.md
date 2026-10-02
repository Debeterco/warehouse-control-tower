# Warehouse Control Tower — Almoxarifado Control Tower

Torre de controle de almoxarifado industrial — **Team 5 / Supply Chain Logistics**.
Curva ABC (Pareto), Ponto de Reposição e alertas de ruptura em tempo real.

**Convenção de idioma:** código, API e banco em **inglês**. Apenas o texto
exibido no dashboard (títulos, rótulos, mensagens de alerta, dados do catálogo)
está em **português**, pois é o que será apresentado à turma e à equipe de planta.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 + Flyway ·
HTML5/CSS3 · Vanilla JS (Fetch) · Apache ECharts 5.5

---

## Como executar

### 1. Requisitos
- JDK 21 ou superior
- PostgreSQL 12 ou superior, rodando localmente
- Maven 3.9+ **ou** o wrapper `mvnw.cmd` (já incluso)

### 2. Banco de dados

O banco `almoxarifado_db` é criado e populado automaticamente pelo Flyway
na primeira execução. Basta ter a senha do PostgreSQL disponível:

```powershell
$env:DB_USER     = 'postgres'
$env:DB_PASSWORD = '<sua-senha>'
$env:DB_NAME     = 'almoxarifado_db'
```

Se o banco ainda não existir:

```powershell
& 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -c "CREATE DATABASE almoxarifado_db ENCODING 'UTF8'"
```

Configurações podem ser sobrescritas por variável de ambiente
(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`).

### 3. Build e execução

```powershell
.\mvnw.cmd package -DskipTests
$env:SERVER_PORT = '8090'      # ver nota sobre a porta 8080 abaixo
java -jar target\warehouse-control-tower.jar
```

Acesse **http://localhost:8090**

> **Porta 8080 ocupada:** nesta máquina a 8080 está tomada pelo `ScadaBR.exe`
> (aplicativo SCADA/HMI de planta). **Não encerte o processo.** Use
> `SERVER_PORT=8090` ou outra porta livre.

### 4. Zerar o banco (antes de apresentar)

O simulador de telemetria roda continuamente e drena o estoque, então o painel
se afasta dos números do seed. Para começar de um estado limpo:

```powershell
$env:DB_PASSWORD = 'sua-senha'
.\reset-db.ps1
```

O script para a aplicação, recria o banco (as 4 migrations rodam de novo),
sobe o app e imprime o estado resultante. Opções:

| Parâmetro | Efeito |
|---|---|
| `-Port 8099` | usa outra porta |
| `-DbName outro_db` | reseta outro banco |
| `-NoStart` | recria o banco sem iniciar a aplicação |
| `-Yes` | pula a confirmação (útil para automação) |

---

## API REST

| Método | Endpoint                                | Descrição                                    |
|--------|-----------------------------------------|----------------------------------------------|
| GET    | `/api/v1/dashboard/kpis`                | Indicadores consolidados do painel            |
| GET    | `/api/v1/supplies/abc-curve`            | Curva ABC / Pareto (classifica e materializa) |
| GET    | `/api/v1/supplies/stockout-alerts`      | Alertas por severidade (filtro `?severity=`)  |
| GET    | `/api/v1/supplies`                      | Catálogo completo de insumos                  |
| GET    | `/api/v1/supplies/{id}/reorder-point`   | Ponto de reposição de um item                 |
| GET    | `/api/v1/supplies/turnover`             | **Giro de estoque** (global e por classe ABC) |
| GET    | `/api/v1/supplies/supplier-performance` | Desempenho por fornecedor                     |
| GET    | `/api/v1/work-orders/fulfilment`        | **Tempo médio de atendimento** por setor      |
| POST   | `/api/v1/simulation/parameters`         | Ajusta a simulação em tempo de execução       |
| GET    | `/api/v1/simulation/parameters`         | Parâmetros e contadores do simulador           |

Severidades: `STOCKOUT` · `CRITICAL` · `WARNING` · `HEALTHY`

Ajuste da simulação (todos os campos são opcionais):

```bash
curl -X POST http://localhost:8090/api/v1/simulation/parameters \
  -H "Content-Type: application/json" \
  -d '{"active": true, "intensity": 2.0, "intervalMs": 3000}'
```

---

## Indicadores da cadeia de suprimentos

Os três indicadores exigidos para o tema da equipe:

### 1. Giro de estoque

```
COGS               = valor das SAÍDAS na janela
InventoryAtStart   = valorAtual + COGS - valorEntrante
EstoqueMedio       = (InventoryAtStart + valorAtual) / 2
Giro               = COGS / EstoqueMedio
Giro anualizado    = Giro x (365 / janela)
Dias de estoque    = janela / Giro
```

Calculado no agregado **e por classe ABC** — o que mostra onde o dinheiro está
preso. Classe C com giro baixo significa capital parado; classe A com giro alto
significa capital girando.

### 2. Ponto de peças críticas

```
Estoque de segurança = consumoMédioDiário x tempoReposicaoDias x fatorSegurança
Ponto de reposição   = (consumoMédioDiário x tempoReposicaoDias) + estoque de segurança
Dias de cobertura    = saldo / consumoMédioDiário
```

Com `safetyFactor = 0.50`, o ponto de reposição equivale a **1,5x** a demanda do
lead time. Severidades: `STOCKOUT` · `CRITICAL` · `WARNING` · `HEALTHY`.

### 3. Tempo médio de atendimento

```
Tempo médio = média(completed_at - created_at)
Mediana     = percentil 50 — menos sensível a outliers
SLA         = % de ordens fechadas dentro da meta (padrão 48h)
```

Calculado apenas sobre ordens **concluídas** — ordens em aberto não têm tempo de
atendimento. Exposto por média, mediana, min, max e por setor solicitante.

> **Nota de fuso horário:** as comparações usam `NOW() AT TIME ZONE 'UTC'`
> porque o Hibernate grava os timestamps em UTC enquanto `NOW()` devolve a hora
> local do servidor. Sem isso a idade das ordens em aberto sairia negativa.

---

## Filtros interativos

Barra de filtros acima dos gráficos, aplicada no cliente (resposta instantânea):

- **Classe ABC** — `Todas` / `A` / `B` / `C`, filtra Pareto, catálogo e rosca
- **Severidade** — `Ruptura` / `Crítico` / `Atenção`, filtra a tabela de alertas
- **Buscar insumo** — casa código ou nome, filtra os três painéis
- **Top N** — limita o Pareto aos N primeiros itens por valor

Ao filtrar, o percentual acumulado do Pareto é **recalculado sobre o subconjunto
visível**, para que a curva termine em 100% do que está na tela.

---

## Simulação de telemetria

`TelemetrySimulatorTask` roda a cada 5 s e gera, sozinho:

- abertura de ordens de serviço e avanço de status (OPEN → IN_PROGRESS → COMPLETED)
- consumo de insumos, proporcional ao consumo médio de cada item
- reposição automática dos itens abaixo do estoque mínimo
- recálculo da curva ABC a cada 10 ciclos

As ordens são criadas com **timestamp retrodatado** (45 min a 4 dias). Sem isso,
uma ordem abriria e fecharia em segundos e o tempo médio de atendimento
colapsaria para minutos — sem significado estatístico e visivelmente errado para
um gerente de suprimentos.

O agendamento é **dinâmico**: o ciclo é reescalonado a cada execução via
`TaskScheduler`, e não por `@Scheduled(fixedDelayString)`. É isso que permite
`POST /api/v1/simulation/parameters` mudar `intervalMs` **sem reiniciar** a
aplicação. Cada unidade de trabalho roda em transação própria
(`TransactionTemplate`) — o agendador nunca segura transação aberta.

---

## Regras de negócio

### Curva ABC / Pareto

Itens ordenados por valor imobilizado (`current_quantity * unit_cost`),
classificados pelo percentual **acumulado** do valor total:

| Classe | Regra                                                                 |
|--------|-----------------------------------------------------------------------|
| **A**  | do primeiro item até cruzar **80%** do valor (o item que cruza entra em A) |
| **B**  | acumulando até **95%** do valor                                      |
| **C**  | a cauda restante                                                     |

O resultado é gravado em `tb_supply_item.abc_category`. Limiares configuráveis
em `application.yml` (`warehouse.stock.abc-curve`).

### Índice de saúde do estoque (0-100)

`(1 - (ruptura×4 + crítico×2 + atenção×0,5) / (total × 4)) × 100`
— um item zerado pesa mais que dez em atenção.

---

## Simulação de telemetria

`TelemetrySimulatorTask` roda a cada 5 s e gera, sozinho:

- abertura de ordens de serviço e avanço de status (OPEN → IN_PROGRESS → COMPLETED)
- consumo de insumos, proporcional ao consumo médio de cada item
- reposição automática dos itens abaixo do estoque mínimo
- recálculo da curva ABC a cada 10 ciclos

O agendamento é **dinâmico**: o ciclo é reescalonado a cada execução via
`TaskScheduler`, e não por `@Scheduled(fixedDelayString)`. É isso que permite
`POST /api/v1/simulation/parameters` mudar `intervalMs` **sem reiniciar** a
aplicação. Cada unidade de trabalho roda em transação própria
(`TransactionTemplate`) — o agendador nunca segura transação aberta.

---

## Estrutura

```
src/main/java/com/fabrica/controltower/
├── WarehouseControlTowerApplication.java
├── config/        CorsConfig, SchedulingConfig
├── controller/    KpiController, SupplyController, WorkOrderController,
│                  SimulationController
├── dto/           KpiSummaryDTO, AbcCurveDTO, StockoutAlertDTO,
│                  InventoryTurnoverDTO, WorkOrderFulfilmentDTO,
│                  SupplierPerformanceDTO, SimulationParametersDTO
├── entity/        SupplyItem, WorkOrder, StockMovement
├── repository/    SupplyRepository, WorkOrderRepository, StockMovementRepository
├── service/       AbcCurveService, StockService, KpiService, SupplyChainService
└── scheduler/     TelemetrySimulatorTask

src/main/resources/
├── application.yml
├── db/migration/  V1__create_tables.sql
│                  V2__seed_initial_data.sql
│                  V3__supply_chain_indexes.sql
│                  V4__work_order_history.sql
└── static/        index.html, css/main.css, js/app.js,
                   js/charts/{paretoChart,gaugeChart,supplyChainChart}.js,
                   js/services/api.js, vendor/echarts.min.js
```

**Nota:** o ECharts é servido localmente de `static/vendor/` para que o painel
funcione sem internet — comum em redes de planta.

---

## Banco de dados

| Tabela              | Conteúdo                                                              |
|---------------------|-----------------------------------------------------------------------|
| `tb_supply_item`    | catálogo, saldo, estoque mínimo, lead time, custo, categoria ABC       |
| `tb_work_order`     | ordens de manutenção (setor, status, datas)                            |
| `tb_stock_movement` | trilha imutável de entradas/saídas com saldo resultante                |

O Flyway é dono do schema (`ddl-auto: validate`), então divergência entre
entidade e banco derruba a aplicação no arranque em vez de corromper dados.

**Dados descritivos em português:** nomes de insumos, setores e motivos de
manutenção permanecem em pt-BR de propósito — é o que aparece impresso nas
etiquetas e é exibido no dashboard. Identificadores, colunas e rotas são
todos em inglês.