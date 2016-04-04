package code

/**
  * @author eiennohito
  * @since 2015/11/16
  */
object Sizes {
  val kBorder = 1.2 * 1024

  def readable(size: Long): String = {
    var sz = size.toDouble
    if (sz < kBorder) {
      return s"$size B"
    }

    sz /= 1024
    if (sz < kBorder) {
      return f"$sz%.2f KB"
    }

    sz /= 1024
    if (sz < kBorder) {
      return f"$sz%.2f MB"
    }

    sz /= 1024
    if (sz < kBorder) {
      return f"$sz%.2f GB"
    }

    sz /= 1024
    return f"$sz%.2f TB"
  }
}
