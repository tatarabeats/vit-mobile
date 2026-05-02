# Release Build (Play Console 提出用 AAB)

## 1. Release 用 keystore 生成（一度だけ）

ローカル PC で keytool を使う（Java JDK が必要）:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias vitmobile-release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <STORE_PASSWORD> \
  -keypass <KEY_PASSWORD> \
  -dname "CN=Arita Gajirou, OU=Arita Gajirou, O=VIT Mobile, L=Tanabe, ST=Wakayama, C=JP"
```

**重要**: `release.keystore` は絶対に紛失するな。これを失うと、Play Console に同じアプリ ID で更新版を出せなくなる（アプリを実質的に作り直すしかなくなる）。複数の場所にバックアップする。

## 2. base64 エンコード

```bash
# Git Bash / Linux
base64 -w 0 release.keystore > release.keystore.b64

# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Out-File -Encoding ascii release.keystore.b64
```

## 3. GitHub Secrets 登録

リポジトリ Settings → Secrets and variables → Actions → New repository secret で以下を登録:

| Secret 名           | 値                                |
| ------------------- | --------------------------------- |
| `KEYSTORE_BASE64`   | `release.keystore.b64` の中身     |
| `KEYSTORE_PASSWORD` | keytool で設定した STORE_PASSWORD |
| `KEY_ALIAS`         | `vitmobile-release`               |
| `KEY_PASSWORD`      | keytool で設定した KEY_PASSWORD   |

CLI で登録する場合:

```bash
gh secret set KEYSTORE_BASE64 < release.keystore.b64
gh secret set KEYSTORE_PASSWORD --body "<STORE_PASSWORD>"
gh secret set KEY_ALIAS --body "vitmobile-release"
gh secret set KEY_PASSWORD --body "<KEY_PASSWORD>"
```

## 4. AAB ビルド実行

```bash
gh workflow run build-release.yml
```

または GitHub の Actions タブから "Build Release AAB" ワークフローを手動トリガー。

完了後、Artifact から `vit-mobile-aab` をダウンロード → 中身の `vit-mobile-release.aab` を Play Console にアップロード。

## 5. 注意点

- `targetSdk = 31` のままでは Play Store の新規アプリ要件（targetSdk 34 以上）を満たさない。Play Console 提出前に `app/build.gradle.kts` の `targetSdk` を 34 に上げる必要あり
- targetSdk を 34 に上げると Android 13+ で Accessibility Service の "Restricted Settings" ガードが有効になる。サイドロード時のセットアップ手順は変わらず可能だが、Play Store 経由でインストールされた場合はガード自体がバイパスされる
- バージョン更新時は `versionCode` を必ず +1、`versionName` も上げる
- `release.keystore` は `.gitignore` 済みであることを確認（既に gitignore されてるはず）
