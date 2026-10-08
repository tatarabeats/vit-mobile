# VIT Mobile: 対象アプリ使用中のデバッグ自動停止

作業日: 2026-10-08。作業ブランチ: `secure-apps`。基準HEAD: `908bc65a2b8543b4d089a08050d86fadff585f5f`。

## 実装した動作

- Shizukuの既存UserService接続時、`WRITE_SECURE_SETTINGS` が未付与なら、現在のAndroidユーザーに対して `pm grant --user <userId> com.shunp.vitmobile android.permission.WRITE_SECURE_SETTINGS` を実行。実行後も `checkSelfPermission` で確認する。AIDLの既存IDは維持し、`runShell = 4` を末尾に追加。任意のシェル文字列は実行できず、この固定形式のgrantだけを許可する。
- Accessibilityのウィンドウ変更と起動時の前面判定を接続。VIT自身・IME・System UIを無視。イベントのパッケージを使う場合も、フォーカスされたアプリウィンドウのIDを照合する。起動直後のツリー未取得・イベント欠落に備え、サービス接続中は1秒ごとにも判定する。
- 対象アプリに入ると、元の3設定とsecure状態をPrefsへ同期commitしてから、ShizukuへSTOPを明示的なパッケージ指定で送信。1秒後にWi-Fiデバッグ、USBデバッグ、開発者向けオプションの順に0を書き込む。権限・記録保存が確認できない場合はShizukuを停止しない。
- 通常アプリ（ランチャーを含む）へ移ると1.5秒待ち、開発者向けオプション、USB、Wi-Fiの順に元の値を復旧。元が0だったものは0を維持。2秒待ってSTARTを送信。対象アプリに戻れば復旧予約を無効化する。
- START後10秒でbinderが戻らなければ1回だけ再送。送信回数・次回期限も保存し、プロセス再起動で無制限に再送されないようにする。再送後も戻らなければログと復旧記録を保持し、遅いbinder到着は引き続き受け付ける。
- 対象アプリへ戻った後、以前のSTARTに対応するbinderが遅れて届く競合も処理。停止待ちの途中や通常アプリへの猶予時間中でも、secure状態なら新たにSTOPを送る。
- binder復旧時は既存の `addBinderReceivedListenerSticky` → `refresh()` → UserService再接続でスワイプ監視を再開。短時間の切替でbinderが生き残っていた場合にも明示的にrefreshする。
- Accessibility再接続時に保存状態と現在の前面アプリを照合。対象アプリならオフを維持し、通常アプリなら復旧する。Accessibility切断時も復旧を予約。Accessibility未接続でVITを起動した場合も復旧経路を用意。
- 「アプリ」タブに「デバッグを切るアプリ」カードを追加。既存の選択画面、検索、アイコン表示、長押し削除を再利用。空の選択も保存でき、未インストールの既定項目は保持される。
- 記録は既存の `filesDir/autosend.log` に `secure: ...` で出力する。

## 変更ファイル

| ファイル | 内容 |
|---|---|
| `app/src/main/AndroidManifest.xml` | WRITE_SECURE_SETTINGS宣言 |
| `app/src/main/aidl/com/shunp/vitmobile/IShizukuTouchService.aidl` | 既存IDを維持してrunShell追加 |
| `app/src/main/java/com/shunp/vitmobile/ShizukuTouchService.kt` | 許可したgrantコマンドのみ実行、10秒timeout |
| `app/src/main/java/com/shunp/vitmobile/ShizukuSwipeMonitor.kt` | 初回grant、secure中の接続抑制、UserService互換バージョン1→2 |
| `app/src/main/java/com/shunp/vitmobile/SecureAppMode.kt` | 新規: Android非依存の状態遷移・復旧・再送制御 |
| `app/src/main/java/com/shunp/vitmobile/SecureAppsController.kt` | 新規: Android設定・broadcast・binder・ログ接続 |
| `app/src/main/java/com/shunp/vitmobile/Prefs.kt` | 35件の既定パッケージと復旧記録 |
| `app/src/main/java/com/shunp/vitmobile/InputAccessibilityService.kt` | 前面判定、起動時復旧、ライフサイクル接続 |
| `app/src/main/java/com/shunp/vitmobile/MainActivity.kt` | アプリ選択、長押し削除、復旧入口 |
| `app/src/main/res/layout/activity_main.xml` | 対象アプリカード |
| `app/src/test/java/com/shunp/vitmobile/SecureAppModeTest.kt` | 新規: 状態遷移の14テスト |
| `tools/check_secure_apps.py` | 新規: Androidビルド不要の静的整合性検査 |
| `docs/secure-apps.md` | この実装記録・パッケージ名の根拠 |

アプリの `versionCode=77`、`versionName=0.9.39`、compileSdk=34、targetSdk=30、依存関係は変更していない。変更した `.version(2)` はShizuku UserServiceの互換バージョンであり、アプリのversionCodeではない。commit/push・ビルド・端末への配布は行っていない。

## 既定対象アプリと根拠（35パッケージ）

各リンクは実際に確認した提供者のGoogle Play掲載ページ。URLの `id` がAndroidのパッケージ名の根拠。名称の変更があっても同一IDを使用する。不確かな候補は含めていない。

| アプリ | パッケージ名 | 根拠 |
|---|---|---|
| 三井住友カード Vpass | `com.smbc_card.vpass` | [Google Play](https://play.google.com/store/apps/details?id=com.smbc_card.vpass) |
| 三井住友銀行 / Olive | `jp.co.smbc.direct` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.smbc.direct) |
| 楽天カード | `jp.co.rakuten.kc.rakutencardapp.android` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.rakuten.kc.rakutencardapp.android) |
| 楽天銀行 | `jp.co.rakuten_bank.rakutenbank` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.rakuten_bank.rakutenbank) |
| PayPay / PayPayカード | `jp.ne.paypay.android.app` | [Google Play](https://play.google.com/store/apps/details?id=jp.ne.paypay.android.app) |
| PayPay銀行 | `jp.co.japannetbank.smtapp.balance` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.japannetbank.smtapp.balance) |
| MyJCB | `jp.co.jcb.my` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.jcb.my) |
| セゾンPortal | `jp.co.saisoncard.android.saisonportal` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.saisoncard.android.saisonportal) |
| エポスアプリ | `jp.co.eposcard.epossupportapp` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.eposcard.epossupportapp) |
| イオンウォレット / AEON Pay | `jp.co.aeon.credit.android.wallet` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.aeon.credit.android.wallet) |
| dカード | `com.nttdocomo.dcard` | [Google Play](https://play.google.com/store/apps/details?id=com.nttdocomo.dcard) |
| au PAY | `jp.auone.wallet` | [Google Play](https://play.google.com/store/apps/details?id=jp.auone.wallet) |
| 三菱UFJ銀行 | `jp.mufg.bk.applisp.app` | [Google Play](https://play.google.com/store/apps/details?id=jp.mufg.bk.applisp.app) |
| みずほ銀行 / みずほダイレクト | `jp.co.mizuhobank.banking` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.mizuhobank.banking) |
| りそなグループ | `jp.co.resona_gr.ss.SmartApp` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.resona_gr.ss.SmartApp) |
| ゆうちょ通帳 | `jp.japanpost.jp_bank.bankbookapp` | [Google Play](https://play.google.com/store/apps/details?id=jp.japanpost.jp_bank.bankbookapp) |
| ゆうちょ認証 | `jp.japanpost.jp_bank.FIDOapp` | [Google Play](https://play.google.com/store/apps/details?id=jp.japanpost.jp_bank.FIDOapp) |
| 住信SBIネット銀行（掲載名: ドコモの銀行 ドコモSMTBネット銀行） | `jp.co.netbk` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.netbk) |
| ソニー銀行 | `net.moneykit.SonyBankApp` | [Google Play](https://play.google.com/store/apps/details?id=net.moneykit.SonyBankApp) |
| SBI証券 株 | `jp.co.sbisec.hyperkabu2` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.sbisec.hyperkabu2) |
| 楽天証券 iSPEED | `jp.co.rakuten_sec.ispeed` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.rakuten_sec.ispeed) |
| 楽天証券 iSPEED FX | `jp.co.mobileit.ispeed_fx` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.mobileit.ispeed_fx) |
| マネックス証券 | `jp.co.monex.comprehensive` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.monex.comprehensive) |
| Google Wallet | `com.google.android.apps.walletnfcrel` | [Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.walletnfcrel) |
| 楽天ペイ | `jp.co.rakuten.pay` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.rakuten.pay) |
| d払い | `com.nttdocomo.keitai.payment` | [Google Play](https://play.google.com/store/apps/details?id=com.nttdocomo.keitai.payment) |
| auじぶん銀行 | `jp.co.jibunbank.jibunmain` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.jibunbank.jibunmain) |
| Myセブン銀行 | `jp.co.sevenbank.appMysevenbank` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.sevenbank.appMysevenbank) |
| イオン銀行通帳 | `jp.co.aeonbank.android.passbook` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.aeonbank.android.passbook) |
| JRE BANK | `jp.co.rakuten_bank.sapp_jre` | [Google Play](https://play.google.com/store/apps/details?id=jp.co.rakuten_bank.sapp_jre) |
| みんなの銀行 | `com.MinnaNoGinko.bankapp` | [Google Play](https://play.google.com/store/apps/details?id=com.MinnaNoGinko.bankapp) |
| NICOSカード | `jp.mufg.cr.app6` | [Google Play](https://play.google.com/store/apps/details?id=jp.mufg.cr.app6) |
| MDC（三菱UFJニコス） | `jp.mufg.cr.app4` | [Google Play](https://play.google.com/store/apps/details?id=jp.mufg.cr.app4) |
| VポイントPay | `com.smbc_card.vpoint` | [Google Play](https://play.google.com/store/apps/details?id=com.smbc_card.vpoint) |
| グローバルポイント Wallet | `jp.mufg.cr.fam.brand.app1` | [Google Play](https://play.google.com/store/apps/details?id=jp.mufg.cr.fam.brand.app1) |

Oliveは三井住友銀行/Vpassの既存パッケージで扱う。PayPayカードは[公式案内](https://paypay.ne.jp/guide/paypay-card/)でPayPayアプリ内の明細・カード管理を確認したため、架空の独立パッケージは追加していない。既定リストは対象候補であり、35件全てが開発者オプションを検査するとの主張ではない。

## 検証とコンパイル上の未確認点

- このPCではAndroidビルドできないという指定に従い、GradleビルドとJUnit実行は未実施。既存の単体テスト5ファイルは変更していない。
- `python tools/check_secure_apps.py` でXMLの構文、permission宣言、新規view binding用IDの存在と重複、カードの配置、既存AIDLトランザクションの維持、全AIDLメソッドの実装、Gradle設定と既存テストの不変性、35パッケージと出典の対応を検査する。
- 実行結果: **PASS**。XML 35ファイル、既存テスト5ファイル不変、35パッケージと出典の一致を確認。
- `git -c core.safecrlf=false diff --check` を実施。コードレビューで見つかった遅延binder競合を修正し、再レビューで残るCritical/Important指摘なし。
- 追加した14テストは、保存前停止防止、権限不足、1秒待機、対象内遷移、無視イベント、1.5秒復旧猶予、2秒開始待機、途中再入場、1回限定再送、プロセス死亡後のsnapshot/再送数維持、部分的設定失敗、予期しないbinder/遅延binderを扱う。**テストを追加した事実と、実行成功は別。JUnitは未実行。**
- 生成AIDLの `runShell(String): int` とKotlinのoverride、XMLから生成される `ActivityMainBinding.secureApps/pickSecureApps` はソース上の整合を確認したが、実際のコード生成・コンパイルは未確認。別のビルド可能環境で `:app:testDebugUnitTest :app:assembleDebug` が必要。
- 追加ライブラリはない。API呼出しはcompileSdk 34で使う既存のAndroid/Shizuku APIを使用。Kotlinの型チェック・Android Lintを通過したとは扱っていない。

## 実機で残る確認事項

1. 指定されたSTOP→1秒→設定オフの順序には時間差がある。対象アプリが前面イベントより前、またはその1秒以内に起動拒否を確定すると、初回起動を救えない場合がある。自動リロード/強制終了/再起動は仕様外として実装していないため、完全な無操作両立を実機未検証の段階で保証しない。
2. `Settings.Global.putInt` の戻り値と即時読戻しは確認するが、端末メーカー側のADB制御、Wi-Fi信頼/ペアリングの維持、改良版ShizukuがSTARTを受けて起動するまでの動作は実機確認が必要。TCPのポート構成を作り直す処理や、新規ペアリングは追加していない。
3. 元がオフだった設定はオンにしない。元がTCP運用でWi-Fiデバッグがオフなら、そのままの構成でSTARTする。Shizukuが2回のSTARTで戻らなかった場合は設定値を復旧した状態とログを残す。
4. 強制停止・Accessibility権限の剥奪などでAndroidがVITを再起動させない間は処理を実行できない。通常のプロセス再生成/サービス再接続と、明示的なVIT起動の復旧経路を実装した。復旧記録そのものが破損した場合は推測で設定を有効化せず、ログに残す。

技術参照: [Android WRITE_SECURE_SETTINGS](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS)、[Settings.Global](https://developer.android.com/reference/android/provider/Settings.Global)、[改良版Shizuku](https://github.com/thedjchi/Shizuku)。START/STOPのactionとsetPackageは依頼時のManifest確認結果に従った。
