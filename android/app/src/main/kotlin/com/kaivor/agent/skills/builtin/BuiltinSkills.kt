package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.Skill

/**
 * Single source of truth for built-in skills registered at runtime.
 * AgentOrchestrator and unit tests both use this list.
 */
object BuiltinSkills {

    fun all(imageApiKey: String = "", imageApiProvider: String = "together"): List<Skill> {
        val skills = mutableListOf<Skill>(
            SwigySkill(),
            ZomatoSearchFirstSkill(),
            ZeptoSkill(),
            BlinkitSkill(),
            YouTubeSkill(),
            InstagramSkill(),
            PhonePeSkill(),
            GPaySkill(),
            PaytmSkill(),
            CREDSkill(),
            MapsSkill(),
            OlaSkill(),
            UberSkill(),
            RapidoSkill(),
            FlipkartSkill(),
            AmazonSkill(),
            WhatsAppSkill(),
            ChromeSkill(),
            ScreenReaderSkill(),
            ReadingConciergeSkill(),
            GmailSkill(),
            FileManagerSkill(),
            CalendarSkill(),
            NotesSkill(),
            SettingsSkill(),
            ContactsSkill(),
            TravelPlannerSkill(),
            RideConciergeSkill(),
            PriceComparatorSkill(),
            FoodDealFinderSkill(),
            BillSplitterSkill(),
            MorningBriefSkill(),
            EmergencySOSSkill(),
            PhoneFinderSkill(),
            GeneralSkill(),
            WeatherSkill(),
            CurrencySkill(),
            QrCodeSkill(),
            PdfCreatorSkill(),
            PptxCreatorSkill(),
        )
        if (imageApiKey.isNotBlank()) {
            skills += ImageGeneratorSkill(imageApiKey, imageApiProvider)
        }
        return skills
    }
}