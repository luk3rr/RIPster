# RIPster
## Descrição

Este projeto implementa uma simulação em Kotlin Native de roteamento por vetor de distância para redes de computadores, utilizando comunicação UDP entre roteadores simulados.
Este programa é o produto de um trabalho prático da disciplina de Redes de Computadores do [Departamento de Ciência da Computação da UFMG](https://dcc.ufmg.br).

O roteador oferece:

* Troca periódica de rotas com vizinhos (mensagens de update)
* Detecção e remoção de rotas e vizinhos obsoletos (stale routes)
* Encaminhamento de pacotes entre nós da rede
* Suporte a topologias configuráveis via arquivos
* Gerenciamento da simulação com múltiplos roteadores usando tmux

## Requisitos

* Linux com shell bash
* tmux instalado
* Permissões sudo para configuração de endereços loopback
* Gradle para build

## Uso do script `simulation.sh`

O script `simulation.sh` facilita o controle da simulação completa, permitindo iniciar, parar, listar topologias e acompanhar logs.

### Comandos disponíveis

```bash
./simulation.sh start [topology] [period]
./simulation.sh stop
./simulation.sh list
./simulation.sh log
```

### Parâmetros

* `start`: inicia a simulação com todos os roteadores configurados
* `stop`: para a simulação e remove configurações temporárias
* `list`: lista as topologias disponíveis dentro da pasta `topology/`
* `log`: lista os arquivos de log dos roteadores e permite seguir um deles em tempo real
* `topology`: nome da topologia a usar (opcional)
* `period`: intervalo em segundos para envio de atualizações (default: 3 segundos)

### Exemplos de uso

Iniciar simulação com topologia `mesh` e período 5 segundos:

```bash
./simulation.sh start mesh 5
```

Parar a simulação:

```bash
./simulation.sh stop
```

Listar topologias disponíveis:

```bash
./simulation.sh list
```

Acompanhar logs dos roteadores:

```bash
./simulation.sh log
```

### Funcionamento interno

* Executa build do projeto Kotlin Native via Gradle
* Configura endereços loopback para simular os IPs dos roteadores locais
* Abre uma sessão tmux com um pane para cada roteador
* Roteadores comunicam-se via UDP com troca periódica de mensagens de roteamento
* Detecta vizinhos e rotas obsoletas, removendo-os para manter tabela atualizada

## Logs e monitoramento

* Logs individuais para cada roteador em `log/router_<ip>.log`
* Use `./simulation.sh log` para selecionar e acompanhar logs em tempo real
* Mensagens de neighbors DOWN indicam vizinhos não responsivos ou fora da rede