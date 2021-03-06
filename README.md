# Processador de arquivos em batch

## Instalar na máquina (testado em Ubuntu 20.10):

```
jdk-11 (usado openjdk zulu)
maven 3.6.3
docker
activemq porta padrão (usando docker)
cassandra porta padrão (usando docker)
```

Comandos docker para o `Cassandra` e `ActiveMQ` (Execute no terminal, assumindo que seu usuário esteja no grupo docker):

```
docker run -p 9042:9042 --rm --name cassandra -d cassandra
docker run -p 61616:61616 -p 8161:8161 --rm --name activemq -d rmohr/activemq
```

Após ter o banco `Cassandra` e o `ActiveMQ` rodando em sua máquina local, siga para o próximo passo.

## Criar imagem docker

Na raiz do projeto `k8s-file-processor`, dentro de cada uma das seguintes pastas:

```
batch-file-chunk-worker, customer-api, sales-api e salesman-api
```

Execute no terminal:

```
mvn package
```

Exclusivamente para a aplicação batch-file-reader execute:

```
mvn package -Dsystem.owner.userName=$USER
```

## Aplicações

### Batch File Reader `batch-file-reader`

Aplicação responsável por ler os arquivos na pasta pré-configurada, dividir os lotes de arquivo de acordo com a configuração, para cada lote é delegado a execução à aplicação chunk-worker.

### Batch File Chunk Worker `batch-file-chunk-worker`

Aplicação responsável por ler os lotes de arquivos enviados pela aplicação File Reader, responsável por classificar a linha e produzir mensagens para as filas em que as aplicações API estão consumindo.


### Salesman API `salesman-api`

Aplicação responsável por receber as linhas referêntes aos vendedores, contidas nos arquivos de importação.

### Sales API `sales-api`

Aplicação responsável por receber as linhas referêntes às vendas, contidas nos arquivos de importação.

### Customer API `sales-api`

Aplicação responsável por receber as linhas referêntes aos clientes, contidas nos arquivos de importação.

## Deploy das aplicações

Crie uma pasta chamada `data` na sua pasta `home`. Para isso execute:

```
mkdir ~/data
```

Em seguida execute cada uma das linhas abaixo:

```
docker run --network=host --name salesman-api -d alissonvisa/salesman-api:0.0.1-SNAPSHOT
docker run --network=host --name sales-api -d alissonvisa/sales-api:0.0.1-SNAPSHOT
docker run --network=host --name customer-api -d alissonvisa/customer-api:0.0.1-SNAPSHOT
docker run --network=host --name chunk-worker -d alissonvisa/batch-file-chunk-worker:0.0.1-SNAPSHOT
docker run --name file-reader --user $USER --network=host -e CHUNK_SIZE=120 -v $HOME/data:/app/file-input/data -d alissonvisa/batch-file-reader:0.0.1-SNAPSHOT
```

### Processando Arquivos

Verifique se dentro da pasta `~/data` foram criadas automaticamente as pastas `in` e `out`. Caso não tenham sido criadas como o esperado, verifique a sessão `Troubleshooting`.

Para processar arquivos inclua-os na pasta `$HOME/data/in`, com a extensão `.dat`.
O resultado do arquivo processado deverá aparecer em `$HOME/data/out` com final `.done.dat`

### Visualizando logs da aplicação

Para acompanhar os logs de determinada aplicação execute:

```
docker logs -f <container_name>
```
Exemplos:
```
docker logs -f file-reader
docker logs -f chunk-worker
```

## Troubleshooting

Não é recomendado o uso de Docker no Windows para esse cenário.
Caso os diretórios da aplicação `file-reader`, não sincronizem com seu sistema local de arquivos, deve-se adicionar o arquivo diretamente no container docker.
Para adicionar os arquivos no container docker da aplicação `file-reader`, para serem processados, execute:

```
docker cp $HOME/data/in/* file-reader:/app/file-input/data/in/
```

Para obter os arquivos de resultado, extraindo de dentro do container para seu sistema local de arquivos, execute:

```
docker cp file-reader:/app/file-input/data/out/ $HOME/data/out/
```

Os comandos acima assumem que o nome do container da aplicação `batch-file-reader`, seja `file-reader`

Esse problema está relacionado a alguma incompatibilidade entre o volume criado e as pastas do container. Vale se atentar ao comando `mvn package -Dsystem.owner.userName=$USER` onde $USER é uma variável de ambiente contendo seu usuário local logado na máquina. Se o comando foi executado corretamente, recomenda-se parar o container e criá-lo novamente com o comando (na tentativa de sincronizar as pastas do volume `-v`):

```
rm -r ~/data
mkdir ~/data
docker stop file-reader
docker rm file-reader
docker run --name file-reader --user $USER --network=host -e CHUNK_SIZE=120 -v $HOME/data:/app/file-input/data -d alissonvisa/batch-file-reader:0.0.1-SNAPSHOT
```

## Escalabilidade

#### Todas as aplicações aceitam réplicas, exceto a `file-reader` que deve rodar somente em 1 instância.
Recomanda-se que utilize pelo menos 3 réplicas ou mais, da aplicação `batch-file-chunk-worker`. Porém, funciona normalmente com apenas 1 instância.

## Comandos Úteis

Parar todos os containers docker:

```
docker container stop $(docker container ls -aq)
```

