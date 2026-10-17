package app.littlenest;

import java.util.List;

public final class Catalog {
    public record Field(String key, String label, String options, boolean numeric) {
        public boolean multiple() { return key.equals("stool") || key.equals("consistency"); }
    }
    public record Kind(String id, String title, String group, boolean timer, Field... fields) {}

    private static Field choice(String key, String label, String options) {
        return new Field(key, label, options, false);
    }
    private static Field text(String key, String label) { return new Field(key, label, "", false); }
    private static Field number(String key, String label) { return new Field(key, label, "", true); }

    public static final List<Kind> KINDS = List.of(
        new Kind("breast", "Breastfeeding", "Feeding", true, choice("beginSide", "Beginning side", "Left|Right|Both"), choice("side", "Ending / current side", "Left|Right|Both"), choice("entryMethod", "Entry method", "Timer|Manual|Not recorded")),
        new Kind("bottle", "Bottle", "Feeding", false, choice("milk", "Milk", "Breast milk|Formula|Mixed|Other"), number("amount", "Amount"), choice("unit", "Total unit", "ml|fl oz"), text("formulaName", "Formula name"),
            number("breastAmount", "Breast milk amount"), choice("breastUnit", "Breast milk unit", "ml|fl oz"), number("formulaAmount", "Formula amount"), choice("formulaUnit", "Formula unit", "ml|fl oz")),
        new Kind("pump", "Pumping", "Feeding", true, choice("side", "Side", "Both|Left|Right"), number("leftAmount", "Left amount"), number("rightAmount", "Right amount"), choice("unit", "Default / total unit", "ml|fl oz"),
            choice("leftUnit", "Left unit", "ml|fl oz"), choice("rightUnit", "Right unit", "ml|fl oz"), number("totalAmount", "Total output (optional)")),
        new Kind("solids", "Solids", "Feeding", false, choice("food", "Food", "Avocado|Banana|Apple|Pear|Peach|Plum|Mango|Melon|Blueberry|Strawberry|Raspberry|Orange|Kiwi|Broccoli|Carrot|Peas|Sweet potato|Pumpkin|Courgette|Spinach|Potato|Cauliflower|Tomato|Cucumber|Oats|Rice|Wheat|Pasta|Bread|Quinoa|Egg|Yogurt|Cheese|Milk|Peanut|Tree nuts|Sesame|Soy|Fish|Shellfish|Chicken|Beef|Lentils|Beans|Tofu|Other"), text("otherFood", "Other food / portion"), choice("reaction", "Reaction", "None noted|Liked it|Disliked it|Possible reaction")),
        new Kind("diaper", "Diaper", "Care", false, choice("diaper", "Diaper", "Wet|Dirty|Mixed|Dry"), choice("rash", "Rash", "Not noted|No|Yes"), choice("blowout", "Blowout", "Not noted|No|Yes"), choice("stool", "Stool colors", "Not noted|Yellow|Brown|Green|Black|Red|Pale"), choice("consistency", "Stool textures", "Not noted|Seedy|Soft|Loose|Hard|Solid|Pebble|Runny|Mushy|Mucous")),
        new Kind("sleep", "Sleep", "Sleep", true, choice("sleepType", "Sleep type", "Not recorded|Nap|Night sleep")),
        new Kind("growth", "Growth", "Health", false, number("weight", "Weight"), choice("weightUnit", "Weight unit", "kg|lb"), number("height", "Length / height"), number("head", "Head circumference"), choice("lengthUnit", "Length unit", "cm|in"), choice("headUnit", "Head circumference unit", "cm|in")),
        new Kind("medicine", "Medication", "Health", false, text("name", "Medicine"), text("dose", "Dose and unit"), text("route", "Route")),
        new Kind("vaccine", "Vaccination", "Health", false, text("name", "Vaccine"), text("dose", "Dose / lot"), text("provider", "Provider")),
        new Kind("temperature", "Temperature", "Health", false, number("temperature", "Temperature"), choice("unit", "Unit", "C|F"), choice("method", "Method", "Not noted|Armpit|Ear|Forehead|Oral|Rectal")),
        new Kind("medical", "Doctor visit", "Health", false, text("name", "Visit / provider"), text("questions", "Questions / results")),
        new Kind("milestone", "Milestone", "Memories", false, choice("milestone", "Milestone", "First smile|Head control|Rolling|Sitting|Crawling|Standing|First steps|First word|First tooth|Other"), text("name", "Memory title")),
        new Kind("tummy", "Tummy time", "Routines", true),
        new Kind("bath", "Bath", "Routines", false),
        new Kind("story", "Story time", "Routines", true, text("name", "Book")),
        new Kind("routine", "Custom routine", "Routines", true, text("name", "Routine name")),
        new Kind("journal", "Journal & photo", "Memories", false, text("name", "Title")),
        new Kind("parentSleep", "Parent sleep", "Parent", true),
        new Kind("hydration", "Hydration", "Parent", false, number("amount", "Amount"), choice("unit", "Unit", "ml|fl oz")),
        new Kind("meal", "Food & nutrition", "Parent", false, text("name", "Meal / snack")),
        new Kind("mood", "Mood", "Parent", false, choice("mood", "Feeling", "Happy|Calm|Grateful|Okay|Tired|Sad|Anxious|Overwhelmed|Irritable"), text("name", "What is on your mind?")),
        new Kind("vitals", "Parent vitals", "Parent", false, number("weight", "Weight"), choice("weightUnit", "Weight unit", "kg|lb"), number("systolic", "Systolic mmHg"), number("diastolic", "Diastolic mmHg"), number("glucose", "Blood glucose"), choice("glucoseUnit", "Glucose unit", "mmol/L|mg/dL"), number("pulse", "Pulse bpm")),
        new Kind("symptom", "Pregnancy & recovery", "Parent", false, choice("symptom", "Symptom", "Morning sickness|Craving|Food aversion|Back pain|Headache|Swelling|Cramps|Bleeding|Incision / tear recovery|Breast discomfort|Other"), choice("severity", "Severity", "Mild|Moderate|Severe"), text("name", "Details")),
        new Kind("selfcare", "Self-care", "Parent", true, choice("activity", "Activity", "Walk|Yoga|Exercise|Rest|Meditation|Snack time|Other"), text("name", "Details")),
        new Kind("appointment", "Appointment & reminder", "Planning", false, text("name", "Appointment / reminder"), text("questions", "Questions for provider"))
    );

    public static Kind get(String id) {
        return KINDS.stream().filter(k -> k.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown activity: " + id));
    }

    private Catalog() {}
}
