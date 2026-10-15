# TODO — fDatabase

Pendências conhecidas deste plugin (15/10/2026). Riscar quando feito. A lista longa e os detalhes estão em `docs/superpowers/specs/2026-10-15-database-layer-design.md` (no monorepo).

## Antes de ir online (servidor real)
- [ ] Fixar no URL JDBC `characterEncoding=UTF-8` e `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` (ou guardar datas em milissegundos).
- [ ] Ligar TLS a sério (`database.ssl: true`) e rever as permissões do utilizador da BD.
- [ ] Colunas de UUID como `CHAR(36) CHARACTER SET ascii` (convenção para os plugins consumidores).

## Qualidade e dívida técnica
- [ ] Os avisos do SLF4J (`NOP logger`) escondem os logs do próprio HikariCP: ligar a um logger (Bukkit/JUL) para diagnosticar problemas do pool.
- [ ] `FDatabase.onDisable`: pôr o `unregister` + `close()` num `try/finally` e acrescentar Javadoc.
- [ ] `MigrationRunner`: corrigir o comentário sobre libertar o lock (com ligações em pool não se liberta ao fechar); um `IOException` ao ler um recurso aparece como "não encontrado" (manter a causa); Javadoc do `migrate`.
- [ ] `split(null)` devolve lista vazia mas `Migrations.parse(null)` lança `NPE`: uniformizar.
- [ ] Dimensionamento: 4 threads do executor vs `minimumIdle` do Hikari igual ao máximo (8 ligações abertas); fila de trabalho sem limite (numa falha da BD as tarefas acumulam-se com 10 s cada).
- [ ] O nome do lock `flawless_migrations` é global ao servidor MySQL: `flawless` e `flawless_test` partilham-no.
- [ ] Valores de ambiente só com espaços ganham ao ficheiro de configuração (host, porta, nome, utilizador falham com mensagem clara): decidir se se faz `trim`.
- [ ] Testes em falta: validação do pool, fallback com variável vazia, instruções SQL com várias linhas, overflow da versão, lista imutável, teste de ordem onde a V2 depende da V1, o `barrier.await` do teste de concorrência sem timeout.

## Ideias
- [ ] Tentar de novo (com espera) quando o MySQL ainda não está a correr no arranque do servidor, em vez de desativar logo.
