package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinItemPersonImage
import dev.jdtech.jellyfin.models.SpatialFinPerson
import java.util.UUID
import org.jellyfin.sdk.model.api.PersonKind

val dummyPerson =
    SpatialFinItemPerson(
        id = UUID.randomUUID(),
        name = "Su Shangqing",
        type = PersonKind.ACTOR,
        role = "Cheng Xiaoshi (voice)",
        image = SpatialFinItemPersonImage(uri = null, blurHash = null),
    )

val dummyPersonDetail =
    SpatialFinPerson(
        id = UUID.randomUUID(),
        name = "Rosa Salazar",
        overview =
            "Rosa Bianca Salazar (/ˈsæləzɑːr/; born July 16, 1985) is an American actress. She had roles in the NBC series Parenthood and the FX anthology series American Horror Story: Murder House. She played the title character in the film Alita: Battle Angel. She appeared in The Divergent Series: Insurgent, Maze Runner: The Scorch Trials and Maze Runner: The Death Cure and also appeared in the Netflix films The Kindergarten Teacher and Bird Box. Rosa Bianca Salazar was born on July 16, 1985. She is of Peruvian and French descent. She grew up in Washington, D.C. and nearby Greenbelt, Maryland. Salazar attended Eleanor Roosevelt High School in Greenbelt, and was active in the school theatre program.",
        images = SpatialFinImages(),
    )
