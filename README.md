# VIT Mobile

音声入力ツール（PC版のVoice Input Tool v3）のAndroid版。
画面上の見えない「起動ゾーン」をダブルタップして音声入力、認識したテキストがフォーカス中の入力欄に自動で貼り付けられる。

## 起動方法（v0.4.0〜）

既定は **透明ゾーン**。画面には何も表示されない。

| 操作                 | 動作                                     |
| -------------------- | ---------------------------------------- |
| ゾーンをダブルタップ | 録音開始                                 |
| 録音中にタップ       | 確定 → フォーカス中の入力欄に挿入        |
| 録音中に長押し       | キャンセル                               |
| 待機中に長押し       | フィードバック録音 → GitHub Issue へ送信 |

- 録音中だけ、ゾーンの中央に小さなドットが出る（通常=赤 / フィードバック=金）
- ゾーンの位置は「起動ゾーンの位置を調整」から。ドラッグ→ダブルタップで確定
- 既定位置は画面右端の縦中央（44×150dp）。この範囲のタップはアプリに届かないので、
  よく触る場所からは外して置く
- 旧来の常時表示マイクに戻すには、設定の「透明ゾーンをダブルタップで起動」をOFF

## フィードバックの送り方

待機中にゾーンを長押しして喋り、タップで確定すると、そのまま
[tatarabeats/vit-mobile](https://github.com/tatarabeats/vit-mobile/issues) の Issue になる
（アプリ版数・端末情報つき）。送信には GitHub のトークンをアプリに保存しておく:

1. [fine-grained token 作成ページ](https://github.com/settings/personal-access-tokens/new) で
   Repository access = `tatarabeats/vit-mobile`、Permissions = **Issues: Read and write** のみ
2. 生成されたトークンをアプリの「フィードバック送信（GitHubトークン）」に貼って保存

トークン未設定・オフライン時は端末内 `feedback.jsonl` に積まれ、トークン保存時か
「未送信のフィードバックを送る」でまとめて送られる。

## 仕組み

- **フロートオーバーレイ** (`SYSTEM_ALERT_WINDOW`): 透明の起動ゾーン（または常時表示マイク）
- **録音 + Groq Whisper API** (`whisper-large-v3-turbo`): 日本語音声認識
- **Accessibility Service** (`ACTION_PASTE`): 現在フォーカスの入力欄にテキストを貼り付け

Gboardと共存。キーボード自体はGboardのまま。

## セットアップ（Galaxy S24）

1. Releases から `vit-mobile-*.apk` をDL
2. APKをインストール（「提供元不明のアプリ」の許可が必要）
3. アプリを起動
4. **Groq APIキー**入力 → 保存（[console.groq.com](https://console.groq.com/keys) で取得）
5. 「他のアプリの上に表示」ON
6. 「ユーザー補助」→ 「VIT Mobile テキスト挿入」をON
7. 「フロートマイクを起動」

## 使い方

- マイクアイコンをタップ → 録音開始（赤くなる）
- もう一度タップ → 録音停止 → 自動で入力欄にテキスト挿入
- アイコンはドラッグで移動可

## ビルド

GitHub Actions で自動ビルド。ローカルビルド不要。

```
git push  # → Actions が APK を生成、artifact として取得可
git tag v0.1.0 && git push --tags  # → Release 作成 + APK 添付
```

## 必要権限

- `RECORD_AUDIO` — 録音
- `INTERNET` — Groq API呼び出し
- `SYSTEM_ALERT_WINDOW` — フロートアイコン
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` — 録音中の常駐
- `BIND_ACCESSIBILITY_SERVICE` — 入力欄への自動貼付け

## 今後

- [ ] 辞書機能（PC版 `dictionary.txt` 同期）
- [ ] Claude Haiku LLM補正（句読点・誤字修正）
- [ ] 録音中の波形表示
- [ ] 履歴
