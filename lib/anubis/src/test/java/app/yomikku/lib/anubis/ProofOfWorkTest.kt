package app.yomikku.lib.anubis

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofOfWorkTest {
    // Reference values from Python's hashlib over the same search.
    @Test
    fun findsTheFirstNonceWithEnoughLeadingZeros() {
        val data = "1e19c654b9652aedf18bb48c4e31b665"
        assertEquals(
            2445L to "000b9fc8837b1a8a7952e44e028a5ae78720a7a39e58b0b381d3d20277c75f2f",
            ProofOfWork.solve(data, 3),
        )
        assertEquals(
            107313L to "00009de6186d8ec9972c6047406ae86b39b533a7fc1d36957a0673f4d72d55ba",
            ProofOfWork.solve(data, 4),
        )
    }
}
