# MCAuth

MCAuth は、Minecraft サーバーへの初回接続時に Discord 認証を要求する Paper プラグインです。

## なぜ必要か

公開や半公開の Minecraft サーバーでは、「Discord コミュニティに参加しているメンバーだけを迎え入れたい」という運用がよくあります。しかし標準のホワイトリストは管理者が手作業で名前を追加する必要があり、参加希望者が増えるほど手間とミスが増えていきます。MCAuth は、初回接続時に Discord での認証を求めることでこの手続きを自動化します。プレイヤー自身が指定の Discord チャンネルでコードを送るだけで、本人確認とホワイトリスト追加が完了するため、管理者の手動作業なしに「Discord にいる人だけが参加できる」サーバーを運用できます。

未認証のプレイヤーがサーバーへ接続すると、認証コードを表示して自動的にキックします。プレイヤーが指定された Discord チャンネルにそのコードを送信すると、認証済みとして保存され、Minecraft のホワイトリストへ自動で追加されます。

## 動作環境

- Paper 1.21
- Java 21
- Discord Bot

## 主な機能

- 初回接続時に認証コードを表示してキック
- Discord の指定チャンネルでコード認証
- 認証成功後にホワイトリストへ自動追加
- 認証済み Minecraft UUID と Discord ユーザー情報を保存
- コード総当たり対策のための失敗回数制限

## ビルド方法

```bash
mvn package
```

ビルド後、プラグイン JAR は次の場所に作成されます。

```text
target/mcauth-1.0.0.jar
```

## 導入方法

1. Discord Developer Portal でアプリケーションと Bot を作成します。
2. Bot の Message Content Intent を有効化します。
3. 認証用チャンネルでメッセージの読み取りと送信ができる権限を Bot に付与します。
4. `target/mcauth-1.0.0.jar` を Paper サーバーの `plugins` フォルダに入れます。
5. サーバーを一度起動します。
6. 生成された `plugins/MCAuth/config.yml` を編集するか、Paper の起動環境に環境変数を設定します。
7. `discord.token` と `discord.channel-id`、または `MCAUTH_DISCORD_TOKEN` と `MCAUTH_DISCORD_CHANNEL_ID` を設定します。
8. サーバーを再起動します。

ホワイトリストを実際に有効化するには、`server.properties` で次の設定も有効にしてください。

```properties
white-list=true
```

## 設定例

```yaml
discord:
  token: "PUT_DISCORD_BOT_TOKEN_HERE"
  channel-id: "PUT_CHANNEL_ID_HERE"

auth:
  code-length: 6
  code-expire-seconds: 300
  max-invalid-attempts: 5
  lockout-seconds: 60
```

### 設定項目

- `discord.token`: Discord Bot の Token
- `discord.channel-id`: 認証コードを受け付ける Discord チャンネル ID
- `auth.code-length`: 認証コードの桁数
- `auth.code-expire-seconds`: 認証コードの有効期限
- `auth.max-invalid-attempts`: 何回間違えたら一時ロックするか
- `auth.lockout-seconds`: 一時ロックする秒数

### 環境変数

次の環境変数を設定すると、対応する `config.yml` の値より優先されます。

```dotenv
MCAUTH_DISCORD_TOKEN=Botのトークン
MCAUTH_DISCORD_CHANNEL_ID=認証チャンネルID
```

環境変数が未設定または空の場合は、`config.yml` にフォールバックします。`.env` ファイルを使う場合は、Paper を起動するシェルやサービスから環境変数として読み込んでください。MCAuth は `.env` ファイル自体を直接読みません。

## 使い方

1. 未認証のプレイヤーが Minecraft サーバーへ接続します。
2. サーバーは認証コードを表示して、そのプレイヤーをキックします。
3. プレイヤーは表示されたコードを Discord の認証チャンネルに送信します。
4. コードが正しければ、プレイヤーは認証済みとして保存されます。
5. その Minecraft UUID がホワイトリストに追加されます。

### 自分の連携を解除する

Discord の認証チャンネルに `/unlink` を実行すると、投稿者本人の Minecraft 連携を解除します。管理者権限は不要です。

- 認証情報とホワイトリスト登録を削除し、Minecraft に接続中なら即座に切断します。
- 他人の名前や ID を指定することはできません。認証チャンネル以外では受け付けません。
- 未連携の場合は、その旨を返信します。
- 再連携する場合は Minecraft サーバーに接続し、表示された新しい認証コードを認証チャンネルに投稿してください。

`/unlink` はBot起動時に認証チャンネルのあるDiscordサーバーへ登録されるスラッシュコマンドです。結果は実行者本人だけに表示されます。Botの導入時は `applications.commands` スコープを含め、利用者に認証チャンネルでの「アプリコマンドを使う」権限を付与してください。

## 保存されるデータ

認証済みデータは次のファイルに保存されます。

```text
plugins/MCAuth/data.yml
```

保存形式の例:

```yaml
authenticated:
  00000000-0000-0000-0000-000000000000:
    name: PlayerName
    discord-user-id: "123456789012345678"
    discord-user-name: "discordName"
```

## 注意事項

- Discord Bot Token は GitHub や公開チャットに絶対に載せないでください。
- `plugins/MCAuth/config.yml` や `plugins/MCAuth/data.yml` は公開しないでください。
- Bot には認証用チャンネルだけを読み書きできる権限を付けることを推奨します。
- 既に生成済みの `config.yml` は、プラグインを更新しても自動では上書きされません。
