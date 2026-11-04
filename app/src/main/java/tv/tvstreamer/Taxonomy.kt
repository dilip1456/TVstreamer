package tv.tvstreamer

/**
 * Live TV sub-categories (pure, data-driven, unit tested). Every type (News, Sports, Movies, …) gets topic / genre
 * sub-categories from keyword rules matched on the channel name and its portal category ("UK | SPORTS",
 * "Sky Sports Cricket"). Rules are checked in order (first match wins), so "NFL" is American Football before
 * "football" is Football. Nothing matches -> "Other". Language is a separate filter and is never a sub-category.
 *
 * Regions (AU, NZ, Caribbean, …) are a secondary tag used only in Sports, so unwanted regional sports channels can be
 * hidden in one go. Codes count only as tags ("AU: Fox Cricket", "|NZ| Sky Sport 1"), words anywhere ("Australia").
 */
object Taxonomy {
    const val OTHER = "Other"
    const val BY_ACTOR = "By Actor & Collections"
    private val SPACES = Regex("\\s+")   // these three first: norm() runs while the tables below are built
    private val MARKS = Regex("\\p{M}+")
    private val NON = Regex("[^\\p{L}\\p{N}]+")

    private fun rules(vararg r: Pair<String, String>): List<Pair<String, List<String>>> =
        r.map { (sub, words) -> sub to words.split(',').map { " " + norm(it) + " " }.filter { it.isNotBlank() } }

    /** Sub-category rules per type. */
    val RULES: Map<String, List<Pair<String, List<String>>>> = mapOf(
        LiveKind.SPORTS to rules(
            "Cricket" to "cricket,willow,ipl,ptv sports,ten cricket,astro cricket,fox cricket,a sports,geo super,psl,bbl,big bash,t20,star sports 1,sports18,sony ten",
            "Tennis" to "tennis,atp,wta,wimbledon,roland garros,us open tennis",
            "American Football" to "nfl,american football,redzone,red zone,ncaa football,college football,sec network,big ten network,acc network",
            "Basketball" to "nba,basketball,wnba,euroleague",
            "Baseball" to "mlb,baseball,yes network,marquee",
            "Hockey" to "nhl,hockey,ice hockey",
            "Golf" to "golf,pga,lpga,ryder cup",
            "Horse Racing" to "horse racing,racing tv,tvg,sky racing,at the races,fanduel racing,racing uk",
            "Motorsport" to "f1,formula 1,formula one,motorsport,motorsports,nascar,motogp,moto gp,indycar,rally,speed,motors tv,motorvision",
            "Combat" to "ufc,wwe,boxing,wrestling,mma,fight,fighting,pbc,bellator,one championship,aew,dazn boxing,kickboxing",
            "Rugby" to "rugby,nrl,super rugby,six nations,premiership rugby",
            "Football" to "football,soccer,premier league,epl,laliga,la liga,serie a,bundesliga,ligue 1,futbol,fútbol,uefa,champions league,mutv,lfc tv,chelsea tv,barca tv,real madrid tv,mls,liga mx,eredivisie,efl,spfl,a league,isl,bein,setanta,premier sports,fa tv",
            "Athletics & More" to "olympic,olympics,athletics,cycling,volleyball,badminton,table tennis,handball,darts,snooker,pool,bowling,swimming,ski,esports,kabaddi,poker"
        ),
        LiveKind.NEWS to rules(
            "Business" to "business,cnbc,bloomberg,fox business,et now,zee business,market,markets,money,cnbc awaaz,cnbc tv18,yahoo finance,finance",
            "Weather" to "weather,accuweather,weather channel",
            "Politics" to "c-span,cspan,parliament,senate,congress,politics,house of commons",
            "International" to "bbc world,cnn international,al jazeera,aljazeera,france 24,france24,dw,euronews,cgtn,nhk world,trt world,rt,i24,wion,sky news,world news,abc australia,arirang,channel newsasia,cna",
            "National" to "news,cnn,msnbc,fox news,newsmax,ndtv,aaj tak,aajtak,times now,republic,abp,news18,tv9,india today,geo news,ary news,dunya,samaa,headlines,noticias,nachrichten,khabar,samachar"
        ),
        LiveKind.MOVIES to rules(
            // Checked on the portal category first (see subOf): "English Actors" > Tom Cruise is By Actor, not Action
            BY_ACTOR to LiveKind.ACTOR_WORDS.joinToString(",") + ",cinema by actor,movies by actor",
            "Premium" to "hbo,showtime,starz,cinemax,epix,mgm,sky cinema,paramount plus,movieplex",
            "Action" to "action,thriller,adrenaline,zee action,utv action",
            "Comedy & Romance" to "comedy,romedy,romance,romantic,love stories,rom com",
            "Horror" to "horror,scream,shudder,fright,terror",
            "Classic" to "classic,classics,tcm,retro,golden,old movies,old films,vintage,star gold,sony max 2,goldmines,gold movies",
            "Family" to "family,hallmark,lifetime movies,kids movies,disney movies",
            "Indie & World" to "indie,independent,sundance,ifc,world cinema,arthouse"
        ),
        LiveKind.ENT to rules(
            "Shopping" to "shop,shopping,qvc,hsn,jewelry,jewellery,ideal world,tv shop,teleshopping,shop lc",
            "Comedy" to "comedy,comedy central,sitcom,laugh,funny",
            "Reality" to "reality,bravo,e entertainment,tlc,we tv,oxygen,real lives",
            "Drama & Series" to "drama,series,serial,serials,soap,novela,novelas,telenovela,dizi,dramas,amc,fx,syfy,tnt,usa network,hallmark",
            "Networks" to "abc,nbc,cbs,fox,the cw,cw,pbs,itv,bbc one,bbc two,bbc three,bbc,channel 4,channel 5,e4,sky one,sky max,tf1,rai,zdf,ard,star plus,colors,sony,zee tv,sun tv,hum tv,ary digital,geo entertainment,star jalsha,zee bangla,star vijay,star maa,asianet",
            "Talk & Game shows" to "talk,game show,quiz,gsn,game"
        ),
        LiveKind.KIDS to rules(
            "Preschool" to "junior,jr,baby,babytv,baby tv,cbeebies,preschool,pre school,nick jr,jim jam,duck tv,tiny,toddler,kinder,little",
            "Education" to "education,educational,learning,school,discovery kids,smart,science kids,curious",
            "Cartoons" to "cartoon,cartoons,toons,toon,boomerang,pogo,nicktoons,sonic,anime,animation,animated,disney xd,cartoonito",
            "Teens & Family" to "teen,teens,family,disney channel,nickelodeon,nick,cbbc,hungama,disney"
        ),
        LiveKind.MUSIC to rules(
            "Classical & Jazz" to "classical,classica,jazz,opera,mezzo,symphony",
            "Country" to "country music,cmt,country",
            "Hip Hop & R&B" to "hip hop,hiphop,rap,rnb,r and b,trace,urban,bet jams,mtv base",
            "Rock & Alternative" to "rock,metal,alternative,indie music,punk",
            "Dance & Electronic" to "dance,edm,electronic,clubland,house,techno,club",
            "Film & Folk songs" to "9xm,b4u music,zoom,mastiii,mastii,sangeet,music india,sun music,isaiaruvi,balle balle,pitaara,mtv beats,9x jalwa,9x tashan,jalwa,raj musix,folk,bhojpuri music,punjabi music,filmi",
            "Radio" to "radio,fm",
            "Pop & Hits" to "pop,hits,mtv,vh1,4music,kiss,box hits,top 40,top hits,chart,charts,now 70s,now 80s,now 90s,music"
        ),
        LiveKind.DOCS to rules(
            "Cooking & Food" to "food,foodfood,food food,cooking,kitchen,chef,cuisine,living foodz,tastemade,recipe",
            "Travel" to "travel,travelxp,travel xp,voyage,explore,adventure",
            "Home & Fashion" to "hgtv,home,fashion,style,lifestyle,living,diy,garden,makeover,zee zest,fox life,magnolia",
            "Crime & Investigation" to "investigation,crime,investigation discovery,true crime,cops,justice,forensic",
            "Nature & Wildlife" to "nature,wild,wildlife,animal,animal planet,nat geo wild,bbc earth,love nature,earth,ocean,planet",
            "History" to "history,smithsonian,military,war,heritage,ancient",
            "Science & Tech" to "science,discovery science,curiosity,nasa,tech,technology,space,knowledge",
            "Documentary" to "documentary,documentaries,docu,docs,discovery,national geographic,nat geo,natgeo,viceland,epic"
        ),
        LiveKind.RELIGIOUS to rules(
            "Sikh" to "gurbani,sikh,ptc simran,sangat,harmandir,golden temple,akaal",
            "Islamic" to "islam,islamic,quran,madani,peace tv,iqraa,paigam,makkah,madina,sunnah,huda,noor",
            "Hindu" to "aastha,sanskar,bhakti,satsang,sadhna,darshan,shubh,vedic,krishna,shri ram,shiv,iskcon,devotional,arihant,hindu",
            "Jain & Buddhist" to "jinvani,jain,paras,buddhist,buddha,dharma",
            "Christian" to "christian,church,gospel,ewtn,daystar,tbn,god tv,catholic,jesus,faith,bible,hillsong,cbn,word network,3abn,trinity,angel"
        ),
        LiveKind.REGIONAL to rules(
            "Shopping" to "shop,shopping,qvc,hsn,teleshopping",
            "Public & Government" to "public,pbs,government,parliament,assembly,doordarshan,dd ",
            "Local & Community" to "local,community,city,metro,regional",
            "General" to "general,variety,generalist,general tv,national,tv"
        ),
        LiveKind.OTHER to rules(
            "Shopping" to "shop,shopping,qvc,hsn,teleshopping",
            "Adult" to "adult,xxx,18+,18 plus,playboy,hustler,brazzers,private",
            "Events & PPV" to "ppv,pay per view,event,events,live event,24/7,247",
            "Radio" to "radio,fm"
        )
    )

    /** Sub-category of a channel of [type] in portal category [cat] named [name]. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun sub(type: String, cat: String, name: String): String = memo.getOrPut("$type\u0000$cat\u0000$name") { subOf(type, cat, name) }
    private fun subOf(type: String, cat: String, name: String): String {
        if (type == LiveKind.NEWS && LiveKind.isLocalNews(cat)) return "Local News"
        val rs = RULES[type] ?: return OTHER
        val n = " " + norm(name) + " "
        val c = " " + norm(cat) + " "
        // Movies: a portal category about actors / collections decides ("ENGLISH ACTORS" > "Die Hard Action"); a
        // person's movie channel ("Tom Cruise Movies") is By Actor too (1.55)
        if (type == LiveKind.MOVIES) {
            val actor = rs.first { it.first == BY_ACTOR }.second
            if (actor.any { c.contains(it) || n.contains(it) } || LiveKind.isPersonMovies(name)) return BY_ACTOR
        }
        // The channel name first ("Sky Sports Cricket" in "UK SPORTS"), then the portal category ("USA | NBA")
        for (text in listOf(n, c)) for ((s, words) in rs) if (words.any { text.contains(it) }) return s
        return OTHER
    }

    /** Regions for Sports: (region, codes used as tags, words found anywhere). */
    private val REGIONS: List<Triple<String, Set<String>, List<String>>> = listOf(
        Triple("Australia", setOf("AU", "AUS"), listOf("australia", "australian", "kayo", "optus sport", "fox sports au", "fox footy", "stan sport", "foxtel")),
        Triple("New Zealand", setOf("NZ"), listOf("new zealand", "sky sport nz", "sky nz", "sky sport now", "spark sport")),
        Triple("Caribbean", setOf("CARIB", "JM", "TT", "BB"), listOf("caribbean", "carib", "jamaica", "trinidad", "barbados", "flow sports", "sportsmax", "rush sports", "guyana")),
        Triple("UK & Ireland", setOf("UK", "GB", "IE", "IRE"), listOf("united kingdom", "british", "sky sports", "bt sport", "tnt sports uk", "premier sports", "ireland", "irish")),
        Triple("USA", setOf("US", "USA"), listOf("united states", "usa", "espn", "fox sports 1", "fs1", "nbc sports", "cbs sports", "bally sports", "msg", "nesn", "yes network")),
        Triple("Canada", setOf("CA", "CAN"), listOf("canada", "canadian", "tsn", "sportsnet", "rds", "tva sports")),
        Triple("India", setOf("IN", "IND"), listOf("india", "indian", "star sports", "sony ten", "sports18", "dd sports", "sony sports")),
        Triple("Pakistan", setOf("PK", "PAK"), listOf("pakistan", "ptv sports", "a sports", "geo super", "ten sports")),
        Triple("Arabic", setOf("AR", "ARAB", "MENA", "KSA", "UAE", "QA"), listOf("arab", "arabic", "ssc", "ad sports", "alkass", "al kass", "dubai sports", "abu dhabi sports", "bein ar")),
        Triple("Africa", setOf("AF", "ZA", "NG"), listOf("africa", "supersport", "nigeria", "kenya")),
        Triple("Latin America", setOf("LATAM", "MX", "AR", "BR", "CO", "CL"), listOf("latam", "latin", "mexico", "méxico", "argentina", "brazil", "brasil", "colombia", "tudn", "tyc", "espn deportes", "sportv")),
        Triple("Europe", setOf("EU", "DE", "FR", "ES", "IT", "PT", "NL", "PL", "TR", "GR", "SE", "NO", "DK"), listOf("europe", "eurosport", "germany", "france", "spain", "italy", "portugal", "netherlands", "poland", "turkey", "greece", "sweden"))
    ).map { (r, codes, words) -> Triple(r, codes, words.map { " " + norm(it) + " " }) }

    private val TAG = Regex("(?:^\\s*([A-Z]{2,5})\\s*[:|\\-])|(?:[|\\[(]\\s*([A-Z]{2,5})\\s*[|\\])])")

    private fun tags(s: String): Set<String> = TAG.findAll(s).mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null } }.toSet()

    /** Region of a sports channel, "" when none is known. */
    fun region(cat: String, name: String): String {
        val t = tags(name) + tags(cat)
        if (t.isNotEmpty()) REGIONS.firstOrNull { (_, codes, _) -> t.any { it in codes } }?.let { return it.first }
        for (text in listOf(" " + norm(name) + " ", " " + norm(cat) + " "))
            REGIONS.firstOrNull { (_, _, words) -> words.any { text.contains(it) } }?.let { return it.first }
        return ""
    }

    // Hidden groups, synced with the hidden set like channels: "grp:Sports", "grp:Sports/Cricket", "grp:Sports/@Australia"
    const val PREFIX = "grp:"
    fun typeKey(type: String) = "$PREFIX$type"
    fun subKey(type: String, sub: String) = "$PREFIX$type/$sub"
    fun regionKey(type: String, region: String) = "$PREFIX$type/@$region"

    fun groupHidden(type: String, sub: String, region: String, hidden: Set<String>): Boolean =
        typeKey(type) in hidden || subKey(type, sub) in hidden || (region.isNotEmpty() && regionKey(type, region) in hidden)

    /** Short label of a hidden-group key ("Sports › Cricket", "Sports › Australia"). */
    fun label(key: String): String = key.removePrefix(PREFIX).replace("/@", " › ").replace("/", " › ")

    /** Lower case, accents off, punctuation to spaces (articles kept: "A Sports" must not become "Sports"). */
    private fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase().replace("&", " and ").replace("+", " plus "), java.text.Normalizer.Form.NFD)
        .replace(MARKS, "").replace(NON, " ").replace(SPACES, " ").trim()
}
