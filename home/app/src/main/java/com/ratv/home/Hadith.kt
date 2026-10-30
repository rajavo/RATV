package com.ratv.home

import android.content.Context

class Hadith(val text: String, val narrator: String, val source: String)

/**
 * Authentic hadiths (from Sahih al-Bukhari and Sahih Muslim), shown one at a time:
 * a new one each time the home screen comes back, and every few minutes while it stays.
 */
object Hadiths {
    private const val BOTH = "متفق عليه"
    private const val BUKHARI = "رواه البخاري"
    private const val MUSLIM = "رواه مسلم"

    val ALL = listOf(
        Hadith("إنما الأعمال بالنيات، وإنما لكل امرئ ما نوى", "عمر بن الخطاب", BOTH),
        Hadith("من كان يؤمن بالله واليوم الآخر فليقل خيراً أو ليصمت", "أبي هريرة", BOTH),
        Hadith("لا يؤمن أحدكم حتى يحب لأخيه ما يحب لنفسه", "أنس بن مالك", BOTH),
        Hadith("المسلم من سلم المسلمون من لسانه ويده", "عبدالله بن عمرو", BOTH),
        Hadith("الدين النصيحة", "تميم الداري", MUSLIM),
        Hadith("لا تغضب", "أبي هريرة", BUKHARI),
        Hadith("الكلمة الطيبة صدقة", "أبي هريرة", BOTH),
        Hadith("من سلك طريقاً يلتمس فيه علماً سهّل الله له به طريقاً إلى الجنة", "أبي هريرة", MUSLIM),
        Hadith("خيركم من تعلّم القرآن وعلّمه", "عثمان بن عفان", BUKHARI),
        Hadith(
            "كلمتان خفيفتان على اللسان، ثقيلتان في الميزان، حبيبتان إلى الرحمن: سبحان الله وبحمده، سبحان الله العظيم",
            "أبي هريرة", BOTH
        ),
        Hadith("ليس الشديد بالصُّرَعة، إنما الشديد الذي يملك نفسه عند الغضب", "أبي هريرة", BOTH),
        Hadith("من صام رمضان إيماناً واحتساباً غُفر له ما تقدّم من ذنبه", "أبي هريرة", BOTH),
        Hadith("الطُّهور شطر الإيمان", "أبي مالك الأشعري", MUSLIM),
        Hadith("اتقوا النار ولو بشق تمرة", "عدي بن حاتم", BOTH),
        Hadith("ما نقصت صدقة من مال", "أبي هريرة", MUSLIM),
        Hadith("إن الله لا ينظر إلى صوركم وأموالكم، ولكن ينظر إلى قلوبكم وأعمالكم", "أبي هريرة", MUSLIM),
        Hadith("لا تحقرنّ من المعروف شيئاً، ولو أن تلقى أخاك بوجه طلق", "أبي ذر", MUSLIM),
        Hadith("من دلّ على خير فله مثل أجر فاعله", "أبي مسعود الأنصاري", MUSLIM),
        Hadith("يسّروا ولا تعسّروا، وبشّروا ولا تنفّروا", "أنس بن مالك", BOTH),
        Hadith("أحب الأعمال إلى الله أدومها وإن قلّ", "عائشة", BOTH),
        Hadith("من لا يَرحم لا يُرحم", "أبي هريرة", BOTH),
        Hadith("إن الله رفيق يحب الرفق في الأمر كله", "عائشة", BOTH),
        Hadith("من بنى مسجداً يبتغي به وجه الله بنى الله له مثله في الجنة", "عثمان بن عفان", BOTH),
        Hadith(
            "الصلوات الخمس، والجمعة إلى الجمعة، ورمضان إلى رمضان، مكفّرات ما بينهن إذا اجتُنبت الكبائر",
            "أبي هريرة", MUSLIM
        ),
        Hadith("صلاة الجماعة أفضل من صلاة الفذّ بسبع وعشرين درجة", "عبدالله بن عمر", BOTH),
        Hadith(
            "من قال: سبحان الله وبحمده، في يوم مائة مرة، حُطّت خطاياه وإن كانت مثل زبد البحر",
            "أبي هريرة", BOTH
        ),
        Hadith("لا يدخل الجنة قاطع", "جبير بن مطعم", BOTH),
        Hadith(
            "انظروا إلى من هو أسفل منكم، ولا تنظروا إلى من هو فوقكم، فهو أجدر ألا تزدروا نعمة الله عليكم",
            "أبي هريرة", MUSLIM
        ),
        Hadith("الحياء لا يأتي إلا بخير", "عمران بن حصين", BOTH),
        Hadith("من يُرد الله به خيراً يفقّهه في الدين", "معاوية بن أبي سفيان", BOTH),
        Hadith("إن الصدق يهدي إلى البر، وإن البر يهدي إلى الجنة", "عبدالله بن مسعود", BOTH),
        Hadith("المؤمن للمؤمن كالبنيان يشدّ بعضه بعضاً", "أبي موسى الأشعري", BOTH),
        Hadith("الدنيا سجن المؤمن وجنة الكافر", "أبي هريرة", MUSLIM)
    )

    /** The next one, going through the whole list before any comes again. */
    fun next(context: Context): Hadith {
        val sp = context.getSharedPreferences("rahome", Context.MODE_PRIVATE)
        val i = (sp.getInt("hadith", -1) + 1).mod(ALL.size)
        sp.edit().putInt("hadith", i).apply()
        return ALL[i]
    }
}
