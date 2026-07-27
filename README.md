# LINE-Chrome-Android-App
Chrome Extension LINEのAndroidアプリケーション  
Chrome ExtensionのLINEをダウンロードたり、[CHRLINE](https://github.com/DeachSword/CHRLINE) を見たり、Sonnet 4.6を使ったりして作った、LLMふつうにすごい  

## アプリの説明とデモ
LINE非公式クライアントのAndroidアプリ  
そこそこ昔にサブ端末機能が追加されたので「でもけっきょく2台までしかログイン出来ないのかよ、足りるわけないだろ😡」って人向けになりそう、それか物好き  

### チャットとフレンド覧
<img src="https://raw.githubusercontent.com/taka-4602/LINE-Chrome-Android-App/refs/heads/main/demo_images/Screenshot_20260727_053741_LINE_Chrome.jpg" width="60%"><br>
<img src="https://raw.githubusercontent.com/taka-4602/LINE-Chrome-Android-App/refs/heads/main/demo_images/Screenshot_20260727_053455_LINE_Chrome.jpg" width="60%">

<br>

なんと2 pane表示に対応、画像はZ Fold5  
通知の権限を与えたら、バックグラウンド通知も来る  

<br>

<img src="https://raw.githubusercontent.com/taka-4602/LINE-Chrome-Android-App/refs/heads/main/demo_images/Screenshot_20260727_053420_One_UI_Home.jpg" width="60%">

<br>

セッションのトークンが死んだら自動で再ログインする機能つきなので、多分通知を見逃すことは無いはず。 もちろん無効に出来る  

その他は基本的なメッセージングアプリと同じ  
- 特定のメッセージへリプライ  
- 画像 / 動画の送受信  
- スタンプの表示

既知の問題は、こっちからスタンプを送信することに対応してないこと  
もちろん不具合やアカウントBANに関しても自己責任でお願いします
## その他
先に必要な機能だけ実装されたPythonライブラリーを作ってそれをもとにAndroidアプリにしてる、例えばオープンチャットの機能は無いなど。 自分が使いたかった機能のみ実装してる自己中クライアント。アプリの言語が英語なのは自分の端末で使ってるフォントが英語しかサポートしてくれないから  
自分の持っている端末が多過ぎるし、なぜかアカウントBANされたのでLINE自体をもともと使っていなかったけど、インターンシップ先の企業がLINEでコミュニケーションを取っていたため使わないといけなかった  
今まではKiwi BrowserにChrome ExtensionのLINEをインストールしてかなり無理やり使っていたけど、さすがに不便過ぎていい方法を模索してたけど無かったので仕方ないね  
友達が「オープンソースにしておけば誰かがアップグレードしてくれるかもよ！」と言ってたのでGitHubへアップロードすることに。 動機がゲスい  
E2EEやログインフローも先人様たちのおかげですぐ攻略出来たので、ここに感謝申し上げます
## コンタクト  
Discord サーバー / https://discord.gg/2vSnNuRmQ6  
Discord ユーザー名 / zkcn  
