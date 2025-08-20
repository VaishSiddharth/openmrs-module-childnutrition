/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.openmrs.module.childnutrition;

import java.util.Date;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
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
		if (encounter == null || encounter.getEncounterType() == null) {
			return;
		}
		
		EncounterType targetType = Context.getEncounterService().getEncounterTypeByUuid(
		    ChildnutritionConfig.CHILD_NUTRITION_ENCOUNTER_TYPE_UUID);
		if (targetType == null || !targetType.getUuid().equals(encounter.getEncounterType().getUuid())) {
			return;
		}
		
		Patient patient = encounter.getPatient();
		ProgramWorkflowService pws = Context.getProgramWorkflowService();
		Program program = pws.getProgramByUuid(ChildnutritionConfig.PROGRAM_CHILD_NUTRITION_UUID);
		if (program == null) {
			return; // Program not installed yet
		}
		
		PatientProgram enrollment = getActiveEnrollment(patient, program);
		if (enrollment == null) {
			enrollment = new PatientProgram();
			enrollment.setPatient(patient);
			enrollment.setProgram(program);
			enrollment.setDateEnrolled(encounter.getEncounterDatetime() != null ? encounter.getEncounterDatetime()
			        : currentDate);
			pws.savePatientProgram(enrollment);
		}
		
		// Sync status from obs
		Concept statusConcept = Context.getConceptService().getConceptByUuid(
		    ChildnutritionConfig.CONCEPT_MALNUTRITION_STATUS_UUID);
		if (statusConcept == null) {
			return;
		}
		Concept statusValue = findLatestCodedObsValue(encounter, statusConcept);
		if (statusValue == null) {
			return;
		}
		
		ProgramWorkflow workflow = null;
		for (ProgramWorkflow wf : program.getWorkflows()) {
			if (wf != null && ChildnutritionConfig.WORKFLOW_MALNUTRITION_STATUS_UUID.equals(wf.getUuid())) {
				workflow = wf;
				break;
			}
		}
		if (workflow == null) {
			return;
		}
		
		ProgramWorkflowState targetState = null;
		for (ProgramWorkflowState s : workflow.getStates()) {
			if (statusValue.equals(s.getConcept())) {
				targetState = s;
				break;
			}
		}
		if (targetState == null) {
			return;
		}
		
		// Determine current state in this workflow
		org.openmrs.PatientState currentState = null;
		for (org.openmrs.PatientState ps : enrollment.getStates()) {
			if (ps.getState() != null
			        && ps.getState().getProgramWorkflow() != null
			        && ps.getState().getProgramWorkflow().equals(workflow)
			        && ps.getEndDate() == null
			        && (currentState == null || (ps.getStartDate() != null && (currentState.getStartDate() == null || ps
			                .getStartDate().after(currentState.getStartDate()))))) {
				currentState = ps;
			}
		}
		
		if (currentState == null || !targetState.equals(currentState.getState())) {
			// Transition via domain API then save program
			enrollment.transitionToState(targetState,
			    encounter.getEncounterDatetime() != null ? encounter.getEncounterDatetime() : currentDate);
			pws.savePatientProgram(enrollment);
		}
	}
	
	private PatientProgram getActiveEnrollment(Patient patient, Program program) {
		ProgramWorkflowService pws = Context.getProgramWorkflowService();
		for (PatientProgram pp : pws.getPatientPrograms(patient, program, null, null, null, null, false)) {
			if (pp.getDateCompleted() == null && Boolean.FALSE.equals(pp.getVoided())) {
				return pp;
			}
		}
		return null;
	}
	
	private Concept findLatestCodedObsValue(Encounter encounter, Concept questionConcept) {
		Concept result = null;
		Date latest = null;
		for (Obs obs : encounter.getAllObs(false)) {
			if (obs.getConcept() != null && questionConcept.equals(obs.getConcept()) && obs.getValueCoded() != null) {
				Date obsDate = obs.getObsDatetime();
				if (latest == null || (obsDate != null && obsDate.after(latest))) {
					latest = obsDate;
					result = obs.getValueCoded();
				}
			}
		}
		return result;
	}
}
