package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSkillManifestTest {

    @Test
    fun `registered builtin skills expose valid unique manifests`() {
        val skills = registeredBuiltinSkills()

        assertTrue("Expected a broad built-in skill set", skills.size >= 40)

        val ids = skills.map { it.manifest.id }
        assertEquals("Active built-in skill ids must be unique", ids.distinct(), ids)

        skills.forEach { skill ->
            val manifest = skill.manifest
            assertTrue("Skill id is blank for ${skill.javaClass.simpleName}", manifest.id.isNotBlank())
            assertTrue("Skill name is blank for ${manifest.id}", manifest.name.isNotBlank())
            assertTrue("Skill version is blank for ${manifest.id}", manifest.version.isNotBlank())
            assertTrue("Skill description is blank for ${manifest.id}", manifest.description.isNotBlank())
            assertTrue("Skill author is blank for ${manifest.id}", manifest.author.isNotBlank())
        }
    }

    private fun registeredBuiltinSkills(): List<Skill> =
        BuiltinSkills.all(imageApiKey = "test", imageApiProvider = "together")
}
