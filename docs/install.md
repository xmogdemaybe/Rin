# Быстрая установка / Quick install

Что поставить, где взять исходники и чем запустить — по дистрибутивам.
Настройки характера и все выключатели: [features.md](features.md), устройство:
[architecture.md](architecture.md).

## What to install / Что установить

### wsl (Windows)
linux users can skip this / линукс юзеры могут это скипнуть

windows users can be happy - this shit wont work native, bcz of tdlib. so its better to run thro wsl. if u already have installed wsl - skip this part // виндувс юзеры могут радоваться - это говно не пойдет нативно, изза тдлиб, он только под линух. изза чего лучше юзать всл. если у вас уже есть всл - скипайте. 

```
wsl --install -d Ubuntu-24.04
```

first run will open distro itself and will ask you to create first user. Первый запуск сам откроет дистрибутив и попросит завести пользователя.

<details>
<summary>amd fx/old chips read this / челы с amd fx и другими старыми чипами, прочитайте это</summary>

 wsl2 runs on hypervisor, and it’s unstable on this type of virtualization and causes BSODs. If you have hardware like this—stick with Linux, give up, or consider yourself warned.
 //
wsl2 работает через гипервизор, и на таком типе виртуализации (у таких процов) оно нестабильное и вызывает бсоды, так что если вы имеете такое железо - будьте добры либо дорога в линукс, либо дропайте эту идею, либо смиритесь, приняв это предупреждение

</details>

### java

```
apt install openjdk-25-jdk           # Debian / Ubuntu / Mint
pacman -S jdk25-openjdk              # Arch / CachyOS / Manjaro
dnf install java-25-openjdk-devel    # Fedora / CentOS Stream
```

Exactly version 25 - don't try a version lower or higher; it just won't work. // ровно 25-я, не пробуйте версию ниже или выше, просто не получится.

### git

```
apt install git              # Debian / Ubuntu / Mint
pacman -S git                # Arch / CachyOS / Manjaro
dnf install git              # Fedora / CentOS Stream
```

## Step 1: get the source / Шаг 1: получить исходники

```
git clone https://github.com/xmogdemaybe/Rin rin && cd rin
```

## Step 2: config and personality / Шаг 2: конфиг и характер

```
cp config.properties.example config.properties
cp -r prompts-example prompts
```

<details>
<summary>eng ver what to fill out</summary>

In `config.properties`, fill in the first seven lines: `api_id` and `api_hash` with
[my.telegram.org](https://my.telegram.org), `phone`, `owner_id`,
`llm_base_url`, `llm_model`, `llm_api_key`. This is a regular Telegram account, not
a Bot API token.

`prompts/` - the bot’s personality; you can edit it freely, and it isn’t committed to the repository.
In the example, `read_only=true`: the bot thinks but doesn’t send anything until you
enable writing yourself.

</details>

<details>
<summary>ру версия что заполнять</summary>

В `config.properties` заполнить первые семь строк: `api_id` и `api_hash` с
[my.telegram.org](https://my.telegram.org), `phone`, `owner_id`,
`llm_base_url`, `llm_model`, `llm_api_key`. Это обычный аккаунт Telegram, а не
бот-токен Bot API.

`prompts/` — характер, правится свободно и в репозиторий не попадает.
В примере `read_only=true`: бот думает, но ничего не отправляет, пока ты сам
не включишь запись.

</details>

## Step 3: run / Шаг 3: запуск — развилка

The first entry is interactive: the code from Telegram is entered into the console, so you need
a real terminal, not a run without a tty. 
Первый вход интерактивный: код из Telegram спрашивается в консоль, поэтому нужен
настоящий терминал, а не запуск без tty.

watch once // Посмотреть один раз:

```
./gradlew run
```

or this (better how i think) // или это (лучше как я считаю)

```
./gradlew installDist
./build/install/rin/bin/rin
```