# VIT Mobile

音声入力ツール（PC版のVoice Input Tool v3）のAndroid版。
画面のどこでもフロートマイクボタンをタップして音声入力、認識したテキストがフォーカス中の入力欄に自動で貼り付けられる。

## 仕組み

- **フロートオーバーレイ** (`SYSTEM_ALERT_WINDOW`): 常時表示のマイクアイコン
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
