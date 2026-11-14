package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupTitleTest {
    @Test fun actorGroupsShowJustTheName() {
        assertEquals("Shah Rukh Khan", GroupTitle.display("Hindi Actor - Shah Rukh Khan", "Movies"))
        assertEquals("Tom Cruise", GroupTitle.display("English Actors: Tom Cruise", "Movies"))
        assertEquals("Brad Pitt", GroupTitle.display("Actors | Brad Pitt", "Movies"))
        assertEquals("Al Pacino", GroupTitle.display("Cinema by Actor: Al Pacino", "Movies"))
        assertEquals("James Bond", GroupTitle.display("Actor James Bond", "Movies"))
        assertEquals("A. R. Rahman", GroupTitle.display("Hindi Music Director - A. R. Rahman", "Movies"))
        assertEquals("Lata Mangeshkar", GroupTitle.display("Singer: Lata Mangeshkar", "Movies"))
        assertEquals("Christopher Nolan", GroupTitle.display("English Directors - Christopher Nolan", "Movies"))
    }

    @Test fun keepsRoleOnlyTitles() {
        assertEquals("Hindi Actors", GroupTitle.display("Hindi Actors", "Movies"))
        assertEquals("Music Videos", GroupTitle.display("Music Videos", "Movies"))
    }

    @Test fun stripsParentContext() {
        assertEquals("Action", GroupTitle.display("Movies - Action", "Movies"))
        assertEquals("Comedy", GroupTitle.display("Movie: Comedy", "Movies"))
        assertEquals("Drama", GroupTitle.display("TV Shows - Drama", "Series"))
        assertEquals("Crime", GroupTitle.display("TV Series: Crime", "Series"))
        assertEquals("News", GroupTitle.display("Live TV - News", "Live TV"))
        assertEquals("Sports", GroupTitle.display("Sports Channels", "Live TV"))
        assertEquals("Cricket", GroupTitle.display("Sports - Cricket", "Sports"))
    }

    @Test fun leavesIdsAndUnrelatedNamesAlone() {
        assertEquals("Action", GroupTitle.display("Action", "Movies"))
        assertEquals("BBC One", GroupTitle.display("BBC One", "Live TV"))
        assertEquals("Star Plus", GroupTitle.display("Star Plus", "Live TV"))
    }
}
