package viper.gobraserver

trait IdeLanguageClientAware {
  def connect(client: IdeLanguageClient): Unit
}