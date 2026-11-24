# MCAuth

MCAuth は、Minecraft サーバーへの初回接続時に Discord 認証を要求する Paper プラグインです。

## なぜ必要か

公開や半公開の Minecraft サーバーでは、「Discord コミュニティに参加しているメンバーだけを迎え入れたい」という運用がよくあります。しかし標準のホワイトリストは管理者が手作業で名前を追加する必要があり、参加希望者が増えるほど手間とミスが増えていきます。MCAuth は、初回接続時に Discord での認証を求めることでこの手続きを自動化します。プレイヤー自身が指定の Discord チャンネルでボタンを押してコードを入力するだけで、本人確認と入場許可の登録が完了するため、管理者の手動作業なしに「Discord にいる人だけが参加できる」サーバーを運用できます。

未認証のプレイヤーがサーバーへ接続すると、認証コードを表示して自動的にキックします。プレイヤーが指定された Discord チャンネルの「認証コードを入力」ボタンを押し、開いたフォームにそのコードを入力すると、認証済みとしてデータベースに保存され、次回から入場できるようになります。入場の可否は MCAuth のデータベースだけで判定し、Minecraft 標準のホワイトリスト（`whitelist.json`）は操作しません。ロールを設定しておけば、同時にそのロールが付き、Discord サーバーのチャンネルを見られるようになります。

## 動作環境

- Paper 1.21
- Java 21
- Discord Bot

## 主な機能

- 初回接続時に認証コードを表示してキック
- Discord の指定チャンネルにボタン付きの案内を設置し、フォームでコード認証
- 認証成功後に指定ロールを付与（任意）
- 認証成功後に指定チャンネルへ歓迎メッセージを投稿（投稿先・文面は設定で変更可能）
- 認証済み Minecraft UUID と Discord ユーザー情報を SQLite データベースに保存（標準のホワイトリストとは別管理）
- コード総当たり対策のための失敗回数制限
- Discordサーバー退出時に連携を解除し、接続中なら即座に切断
- Bot起動時に連携済みユーザーの在籍を確認し、停止中に退出した人の連携も解除
- プロフィールページなど外部から、HTTP窓口でオークション・依頼を操作（任意・トークン設定時のみ）

Bot起動時の在籍確認が終わるまでは、Minecraftへの入場と新規認証を拒否します。認証チャンネルのあるDiscordサーバーを対象に、キャッシュを使わずDiscord APIで確認します。通信・権限・DBエラーは退出として扱わず、認証データを残して再起動まで全員の入場を拒否します。

停止中に退出して、Botが起動する前にDiscordサーバーへ再参加した人は、起動時には在籍しているため連携を維持します。停止中の退出履歴を復元する処理ではありません。

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
2. Bot の Server Members Intent を有効化します（退出したメンバーの認証取り消しに使います）。
3. 認証用チャンネルで「チャンネルを見る」「メッセージを送信」「メッセージ履歴を読む」ができる権限を Bot に付与します。ロールを付ける場合は「ロールの管理」権限も付け、Bot のロールを付与するロールより上に置きます。
4. `target/mcauth-1.0.0.jar` を Paper サーバーの `plugins` フォルダに入れます。
5. サーバーを一度起動します。
6. 生成された `plugins/MCAuth/config.yml` を編集するか、Paper の起動環境に環境変数を設定します。
7. `discord.token` と `discord.channel-id`、または `MCAUTH_DISCORD_TOKEN` と `MCAUTH_DISCORD_CHANNEL_ID` を設定します。
8. サーバーを再起動します。

MCAuth は標準のホワイトリストを使いません。`server.properties` は次の設定にしてください。`white-list=true` のままだと、MCAuth で認証済みでも `whitelist.json` に無い人は入れません。

```properties
white-list=false
```

## 設定例

```yaml
discord:
  token: "PUT_DISCORD_BOT_TOKEN_HERE"
  channel-id: ""
  welcome-channel-id: "1449580988597403651"
  verified-role-id: ""
  auction-channel-id: ""
  request-channel-id: ""

trade-api:
  token: ""
  port: 8111

messages:
  welcome: "{mention} さん、認証が完了しました。ようこそ！"

auth:
  code-length: 4
  code-group-size: 3
  code-expire-seconds: 300
  max-invalid-attempts: 5
  lockout-seconds: 60
```

### 設定項目

- `discord.token`: Discord Bot の Token
- `discord.channel-id`: 認証ボタンを置く Discord チャンネル ID。既定は空で、環境変数でも指定されていなければ認証・入場を受け付けません。Bot 起動時にボタン付きの案内を投稿し、以前の案内があれば書き換えます
- `discord.verified-role-id`: 認証成功時に付けるロール ID。`/unlink` で連携を解除すると外します。空なら付けません
- `discord.welcome-channel-id`: 認証成功後の歓迎メッセージの投稿先。既定は `1449580988597403651`。認証チャンネルと同じサーバー内のチャンネルを指定し、Botに閲覧・送信権限を付けてください。空なら投稿しません
- `discord.auction-channel-id` / `discord.request-channel-id`: 廃止した市場パネルを消すためだけに使います。Bot起動時に直近50件から、Bot自身が投稿した `market:` で始まるボタン付きのメッセージを削除します。空なら認証サーバー内の同名チャンネル（「オークション取引」「依頼掲示板」）を探し、チャンネルの作成・削除はしません
- `messages.welcome`: 歓迎メッセージの文面。既定は `{mention} さん、認証が完了しました。ようこそ！`。`{mention}` は本人へのメンションに置き換わります。空なら投稿しません。DB保存成功時だけ投稿し、失敗・連携済み・Bot再起動時には投稿しません。解除後に再認証した場合は再び投稿します。投稿に失敗しても認証は維持し、ログに記録します
- `messages.panel`: 認証チャンネルに置く案内の本文
- `auth.code-length`: 認証コードの桁数（4〜8、既定は4）
- `auth.code-group-size`: キック画面でコードを区切る桁数。6桁を3にすると「123 456」と表示します。均等に分けられない桁数や0では区切りません。Discordには区切ったまま貼り付けても、空白・ハイフン・全角数字を無視して受け付けます
- `auth.code-expire-seconds`: 認証コードの有効期限
- `auth.max-invalid-attempts`: 何回間違えたら一時ロックするか
- `auth.lockout-seconds`: 一時ロックする秒数
- `trade-api.token`: 取引窓口の Bearer トークン。32文字以上を設定したときだけ起動します。環境変数 `MCAUTH_TRADE_API_TOKEN` がある場合はそちらを優先します
- `trade-api.port`: 取引窓口の待ち受けポート。既定は `8111` で、`0.0.0.0` で待ち受けます

### 環境変数

次の環境変数を設定すると、対応する `config.yml` の値より優先されます。

```dotenv
MCAUTH_DISCORD_TOKEN=Botのトークン
MCAUTH_DISCORD_CHANNEL_ID=認証チャンネルID
MCAUTH_DISCORD_VERIFIED_ROLE_ID=認証後に付けるロールID
MCAUTH_TRADE_API_TOKEN=取引窓口のトークン（32文字以上）
```

環境変数が未設定または空の場合は、`config.yml` にフォールバックします。`.env` ファイルを使う場合は、Paper を起動するシェルやサービスから環境変数として読み込んでください。MCAuth は `.env` ファイル自体を直接読みません。

## 使い方

1. 未認証のプレイヤーが Minecraft サーバーへ接続します。
2. サーバーは認証コードを表示して、そのプレイヤーをキックします。
3. プレイヤーは Discord の認証チャンネルで「認証コードを入力」ボタンを押し、表示されたコードをフォームに入力します。結果は本人だけに表示されます。
4. コードが正しければ、プレイヤーは認証済みとしてデータベースに保存されます。
5. 次回の接続から、その Minecraft UUID は入場できます。

### 自分の連携を解除する

Discord サーバー内のどのチャンネルでも `/unlink` を実行すると、実行者本人の Minecraft 連携を解除します。管理者権限は不要です。認証後に認証チャンネルが見えなくなる設定でも使えます。

- データベースの認証情報を削除し、Minecraft に接続中なら即座に切断します。標準のホワイトリストは操作しません。認証ロールを設定している場合は、そのロールも外します。
- 他人の名前や ID を指定することはできません。
- 未連携の場合は、その旨を返信します。
- 再連携する場合は Minecraft サーバーに接続し、表示された新しい認証コードを認証チャンネルのボタンから入力してください。

`/unlink` はBot起動時に認証チャンネルのあるDiscordサーバーへ登録されるスラッシュコマンドです。結果は実行者本人だけに表示されます。Botの導入時は `applications.commands` スコープを含め、利用者に「アプリコマンドを使う」権限を付与してください。

## 取引窓口（オークション・依頼）

Discord の市場パネルは廃止しました。既存の `market:` ボタン付きメッセージは Bot 起動時に削除され、残っていた画面を押すと「この画面は終了しました。Discordの /profile から操作してください。」と本人だけに返します。取引はプロフィールページ（smp-api 経由）かゲーム内で行います。

MCAuth は `trade-api.token`（または環境変数 `MCAUTH_TRADE_API_TOKEN`）が32文字以上のときだけ、`0.0.0.0:<trade-api.port>`（既定 `8111`）で HTTP 窓口を起動します。すべての要求に `Authorization: Bearer <トークン>` が必要で、違えば 401 を返します。

- `GET /v1/trade?discord=<DiscordユーザーID 17〜20桁>`
  - 未連携: `{"linked":false}`
  - 連携済み: `{"linked":true,"auction":{"listings":[...],"staged":[...],"mine":[...],"bids":[...],"vault":[...]},"requests":{"open":[...],"mine":[...],"accepted":[...]}}`
  - 各配列の要素は AuctionHouse / ContractBoard が返す文字列の項目です。プラグインが無効なら `auction` や `requests` は `null` になります。
- `POST /v1/trade`（本文は4KiBまで）
  - `{"discord":"<ID>","op":"bid|sell|cancel|accept","numbers":[整数...]}`
  - `bid` は `[出品ID, 入札額]`、`sell` は `[預かりID, 開始価格, 即決価格, 時間]`、`cancel` は `[出品ID]`、`accept` は `[依頼ID]` です。
  - 応答は `{"ok":true|false,"message":"日本語の文"}` です。`ok` は「入札しました」「即決価格で落札」「出品しました」「出品を取り消しました」で始まる文と、「依頼「…」を受諾しました。」を成功とします。
  - 未連携なら `ok:false` と「Minecraftアカウントの認証を完了してください。」を返します。

処理は必ずサーバーのメインスレッドで行い、5秒以内に終わらなければ 503 を返します。ロックダウン中・在籍確認中・停止中も 503、入力が不正なら 400 です。トークンは外部に公開しないでください。

## 保存されるデータ

認証済みデータは、次の SQLite データベースに保存されます。ドライバーは Paper に同梱されているため、別途の準備は要りません。

```text
plugins/MCAuth/mcauth.db
```

`authenticated_players` テーブルの列:

| 列 | 内容 |
| --- | --- |
| `uuid` | Minecraft UUID（主キー） |
| `name` | 認証時の Minecraft 名 |
| `discord_user_id` | 認証した Discord ユーザー ID（重複不可。1人1アカウント） |
| `discord_user_name` | 認証時の Discord 名 |

内容は `sqlite3 plugins/MCAuth/mcauth.db "SELECT * FROM authenticated_players;"` などで確認できます。

以前のバージョンが使っていた `plugins/MCAuth/data.yml` は読み込みません。自動では移行しないため、引き継ぐ場合は `data.yml` の内容を上のテーブルへ登録してください。

## 連携・解除のログ

連携の保存と解除に成功したときだけ、サーバーログへ1行ずつ出します。失敗や、連携済み・未連携で何も変わらなかったときは出しません。

```text
[MCAuth] Link event: reason=link player=Steve uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5 discord_id=123456789012345678 discord_name=alice
```

`reason` は次のどれかです。解除のときの名前は、DBに保存されていた認証時の名前です。名前の空白・改行は `_` に、空の値は `-` にします。

| `reason` | いつ出るか |
| --- | --- |
| `link` | 認証コードで連携を保存したとき |
| `unlink` | 本人が `/unlink` で解除したとき |
| `left` | 認証チャンネルのあるDiscordサーバーを退出して解除したとき |
| `startup-check` | Bot起動時の在籍確認で、退出済みとして解除したとき |

## 注意事項

- Discord Bot Token は GitHub や公開チャットに絶対に載せないでください。
- `plugins/MCAuth/config.yml` や `plugins/MCAuth/mcauth.db` は公開しないでください。
- Bot には認証用チャンネルだけを読み書きできる権限を付けることを推奨します。
- 既に生成済みの `config.yml` は、プラグインを更新しても自動では上書きされません。
- `mcauth.db` を開けないなど起動に失敗した場合、MCAuth は停止せず、サーバーを再起動するまで認証済みの人も含めて全員の入場を拒否します。接続中の人もキックします。ただし、MCAuth の JAR が読み込まれない、または他のプラグインやコマンドで MCAuth が無効化された場合は入場チェックがなくなります。
