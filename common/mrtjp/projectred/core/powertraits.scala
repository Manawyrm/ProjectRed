package mrtjp.projectred.core

import mrtjp.projectred.api.IConnectable
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.world.World

/**
 * Trait for things that wish to conduct/use electricity.
 */
trait TPowerConnectable extends IConnectable
{
    /**
     * Getter for the local conductor
     * @param side Side of the required conductor, this
     *             is only used if the tile has multiple
     *             linked conductors (such as voltage transformers).
     * @return The local conductor managed by this object.
     */
    def conductor(side:Int):PowerConductor

    /**
     * This should reach out and grab a conductor from another
     * TPowerConnectable through the method above.
     * @param id Each conductor this can possibly grab should have
     *           a designated ID. This is used internally. Calling
     *           this method with the same ID should yield the
     *           same neighbor conductor.
     * @return The neighbor conductor that corresponds with the id.
     *         Can be NULL if that ID is not connected, etc.
     */
    def conductorOut(id:Int):PowerConductor

    /**
     * Reference to the world. This is used for checking world time
     * to accurately calculate/distribute current.
     * @return The world this TPowerConnectable is in. Should be not null.
     */
    def world:World
}

/**
 * Object held by conducting power tiles, self managed through
 * TPowerConnectable. This model of electrical flow loosely emulates
 * phisics of real world electrical flow in a series circuit.
 * @param parent The "actual" conductor (as in, the tile).
 * @param ids The possible connections to other conductors
 *            this can make.
 */
class PowerConductor(val parent:TPowerConnectable, ids:Seq[Int])
{
    val flows =
    {
        val highest = ids.foldLeft(0){(start, in) => Math.max(start, in)}
        new Array[Double](highest+1)
    }

    var Vloc = 0.0D //local electric potential
    var Iloc = 0.0D //local intensity of electric current

    var Vflow = 0.0D //aquired uncalculated voltage
    var Iflow = 0.0D //aquired uncalculated current

    var time = 0

    def capacitance = 0.0D
    def resistance = 0.01D
    def scaleOfInductance = 0.07D
    def scaleOfParallelFlow = 0.5D

    /**
     * Re-calculates V and I if needed.
     * @return The electric potential, in Volts (V)
     */
    def voltage():Double =
    {
        val tick = parent.world.getTotalWorldTime

        if ((tick&0xFFFF) == time) return Vloc
        time = (tick&0xFFFF).asInstanceOf[Int]

        //calculate voltage
        Iloc = 0.5D*Iflow
        Iflow = 0.0D
        Vloc += 0.05D*Vflow*capacitance
        Vflow = 0.0D

        Vloc
    }

    /**
     * @return The current(I), in Amps (A)
     */
    def amperage =
    {
        voltage()
        Iloc
    }

    /**
     * @return The power(P), in Watts (W)
     */
    def wattage = voltage()*Iloc

    def applyCurrent(I:Double)
    {
        voltage()
        Vflow += I
        Iflow += Math.abs(I)
    }

    def applyPower(P:Double)
    {
        val Ptot = Vloc*Vloc + 0.1D*P*capacitance
        val dP = Math.sqrt(Ptot)-Vloc
        applyCurrent(20.0D*dP/capacitance)
    }

    def drawPower(P:Double)
    {
        val Ptot = Vloc*Vloc - 0.1D*P*capacitance
        val dP = if (Ptot < 0.0D) 0.0D else Math.sqrt(Ptot)-Vloc
        applyCurrent(20.0D*dP/capacitance)
    }

    def update()
    {
        voltage()
        val index = (parent.world.getTotalWorldTime%ids.length).asInstanceOf[Int]
        for (id <- idsFrom(index))
            if (!surge(parent.conductorOut(id), id)) flows(id) = 0.0D

        surgeIn = Seq[PowerConductor]()

        def idsFrom(index:Int) =
        {
            var id2 = Seq[Int]()
            for (i <- 0 until ids.length) id2 :+= ids((index+i)%ids.length)
            id2
        }
    }

    def surge(cond:PowerConductor, id:Int):Boolean =
    {
        if (cond == null) return false
        if (cond.parent == parent) return false
        if (surgeIn.contains(cond)) return true

        val r = resistance+cond.resistance
        var I = flows(id)
        val V = Vloc-cond.voltage()
        flows(id) += (V-I*r)*scaleOfInductance
        I += V*scaleOfParallelFlow

        applyCurrent(-I)
        cond.applySurge(this, I)

        true
    }

    var surgeIn = Seq[PowerConductor]()
    def applySurge(from:PowerConductor, Iin:Double)
    {
        surgeIn :+= from
        applyCurrent(Iin)
    }

    def save(tag:NBTTagCompound)
    {
        for (i <- 0 until flows.length)
            tag.setDouble("flow"+i, flows(i))

        tag.setDouble("vl", Vloc)
        tag.setDouble("il", Iloc)
        tag.setDouble("vf", Vflow)
        tag.setDouble("if", Iflow)
        tag.setInteger("tm", time)
    }

    def load(tag:NBTTagCompound)
    {
        for (i <- 0 until flows.length)
            flows(i) = tag.getDouble("flow"+i)

        Vloc = tag.getDouble("vl")
        Iloc = tag.getDouble("il")
        Vflow = tag.getDouble("vf")
        Iflow = tag.getDouble("if")
        time = tag.getInteger("tm")
    }
}

trait TPowerFlow extends PowerConductor
{
    var charge = 0
    var flow = 0

    override def capacitance = 0.25D

    def getChargeScaled(scale:Int) = Math.min(scale, scale*charge/1000)
    def getFlowScaled(scale:Int) = Integer.bitCount(flow)*scale/32

    def canWork = charge > 600

    abstract override def update()
    {
        super.update()
        charge = (voltage()*10.0D).asInstanceOf[Int]
        flow <<= 1
        if (canWork) flow |= 1
    }

    abstract override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        tag.setInteger("chg", charge)
        tag.setInteger("flow", flow)
    }

    abstract override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        charge = tag.getInteger("chg")
        flow = tag.getInteger("flow")
    }
}