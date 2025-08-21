/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.openmrs.module.childnutrition;

import java.util.Date;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.User;
import org.openmrs.annotation.Handler;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.api.handler.SaveHandler;

/**
 * Handles encounter saves for the Child Nutrition form: enrolls patients into the program when
 * needed, and syncs malnutrition status to the program workflow.
 */
@Handler(supports = Encounter.class)
public class ChildNutritionEncounterSaveHandler implements SaveHandler<Encounter> {
	
	@Override
	public void handle(Encounter encounter, User currentUser, Date currentDate, String reason) {
		ProgramWorkflowService programWorkflowService = Context.getProgramWorkflowService();
		Program program = programWorkflowService.getProgramByUuid(ChildnutritionConfig.PROGRAM_CHILD_NUTRITION_UUID);
		if (program == null) {
			return;
		}
		
		if (!isChildNutritionEncounter(encounter)) {
			return;
		}
		
		PatientProgram patientProgram = getOrCreateActiveProgramEnrollment(programWorkflowService, encounter.getPatient(),
		    program, encounter.getEncounterDatetime());
		Concept statusConcept = Context.getConceptService().getConceptByUuid(
		    ChildnutritionConfig.CONCEPT_MALNUTRITION_STATUS_UUID);
		if (statusConcept == null) {
			return;
		}
		
		Concept statusValue = findLatestCodedObsValue(encounter, statusConcept);
		if (statusValue == null) {
			return;
		}
		
		ProgramWorkflow programWorkflow = getWorkflowByUuid(program, ChildnutritionConfig.WORKFLOW_MALNUTRITION_STATUS_UUID);
		if (programWorkflow == null) {
			return;
		}
		
		ProgramWorkflowState targetState = getStateByConcept(programWorkflow, statusValue);
		if (targetState == null) {
			return;
		}
		
		patientProgram.transitionToState(targetState, encounter.getEncounterDatetime());
		patientProgram.setLocation(encounter.getLocation());
		
		if (targetState.getTerminal()) {
			Date enrolled = patientProgram.getDateEnrolled();
			if (enrolled != null && encounter.getEncounterDatetime() != null
			        && encounter.getEncounterDatetime().before(enrolled)) {
				patientProgram.setDateCompleted(enrolled);
			} else {
				patientProgram.setDateCompleted(encounter.getEncounterDatetime() != null ? encounter.getEncounterDatetime()
				        : currentDate);
			}
		}
		programWorkflowService.savePatientProgram(patientProgram);
	}
	
	private boolean isChildNutritionEncounter(Encounter encounter) {
		return encounter != null && encounter.getEncounterType() != null
		        && ChildnutritionConfig.CHILD_NUTRITION_ENCOUNTER_TYPE_UUID.equals(encounter.getEncounterType().getUuid());
	}
	
	private PatientProgram getOrCreateActiveProgramEnrollment(ProgramWorkflowService programWorkflowService,
	        Patient patient, Program program, Date enrolledOn) {
		PatientProgram activePatientProgram = getActiveProgramEnrollment(programWorkflowService, patient, program);
		if (activePatientProgram != null) {
			return activePatientProgram;
		}
		PatientProgram patientProgram = new PatientProgram();
		patientProgram.setPatient(patient);
		patientProgram.setProgram(program);
		patientProgram.setDateEnrolled(enrolledOn);
		return programWorkflowService.savePatientProgram(patientProgram);
	}
	
	private PatientProgram getActiveProgramEnrollment(ProgramWorkflowService programWorkflowService, Patient patient,
	        Program program) {
		for (PatientProgram patientProgram : programWorkflowService.getPatientPrograms(patient, program, null, null, null,
		    null, false)) {
			if (patientProgram.getDateCompleted() == null && patientProgram.getVoided().equals(Boolean.FALSE)) {
				return patientProgram;
			}
		}
		return null;
	}
	
	private Concept findLatestCodedObsValue(Encounter encounter, Concept questionConcept) {
		Concept result = null;
		Date latest = null;
		for (Obs obs : encounter.getAllObs(false)) {
			if (questionConcept.equals(obs.getConcept()) && obs.getValueCoded() != null) {
				Date obsDate = obs.getObsDatetime();
				if (latest == null || (obsDate != null && obsDate.after(latest))) {
					latest = obsDate;
					result = obs.getValueCoded();
				}
			}
		}
		return result;
	}
	
	private ProgramWorkflow getWorkflowByUuid(Program program, String workflowUuid) {
		for (ProgramWorkflow programWorkflow : program.getWorkflows()) {
			if (programWorkflow != null && workflowUuid.equals(programWorkflow.getUuid())) {
				return programWorkflow;
			}
		}
		return null;
	}
	
	private ProgramWorkflowState getStateByConcept(ProgramWorkflow programWorkflow, Concept concept) {
		for (ProgramWorkflowState programWorkflowState : programWorkflow.getStates()) {
			if (concept.equals(programWorkflowState.getConcept())) {
				return programWorkflowState;
			}
		}
		return null;
	}
}
