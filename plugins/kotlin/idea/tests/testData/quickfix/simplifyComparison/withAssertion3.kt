// "Simplify comparison" "true"
// WITH_STDLIB
fun test() {
    val s = ""
    assert(<caret>s != null && false)
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.SimplifyComparisonFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.fixes.SimplifyComparisonFixFactory$SimplifyComparisonFix