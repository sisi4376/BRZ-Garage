@file:Suppress("DEPRECATION")
package com.brz.obdstudio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BleReplay(val context: Context,val tape: Tape,val speed: Double,val loop: Boolean,val cancelled: ()->Boolean):AutoCloseable {
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val adapter=manager.adapter?:error("手机没有蓝牙")
    private var server:BluetoothGattServer?=null
    private val events=LinkedBlockingQueue<String>()
    private val notifications=LinkedBlockingQueue<Int>()
    private val requests=LinkedBlockingQueue<Pair<BluetoothDevice,ByteArray>>(128)
    private val subscribed=ConcurrentHashMap.newKeySet<String>()
    private val disconnected=ConcurrentHashMap.newKeySet<String>()
    private val engines=mutableMapOf<String,ReplayElm>()
    private val tx=BluetoothGattCharacteristic(uuid(0xfff2),BluetoothGattCharacteristic.PROPERTY_NOTIFY,0)
    private val advertiseCallback=object:AdvertiseCallback(){
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings){events.offer("ready")}
        override fun onStartFailure(errorCode: Int){events.offer("ERROR:BLE 广播失败 $errorCode")}
    }
    private val callback=object:BluetoothGattServerCallback(){
        override fun onServiceAdded(status: Int,service: BluetoothGattService){events.offer(if(status==0)"service" else "ERROR:GATT 服务创建失败 $status")}
        override fun onConnectionStateChange(device: BluetoothDevice,status: Int,newState: Int){
            if(newState==BluetoothProfile.STATE_CONNECTED)disconnected.add(device.address)
            if(newState==BluetoothProfile.STATE_DISCONNECTED){subscribed.remove(device.address);disconnected.add(device.address);StudioState.message("仪表断开 · 等待重新连接")}
        }
        override fun onDescriptorWriteRequest(d: BluetoothDevice,id: Int,descriptor: BluetoothGattDescriptor,prepared: Boolean,response: Boolean,offset: Int,value: ByteArray){
            val valid=descriptor.uuid==uuid(0x2902)&&!prepared&&offset==0&&(value.contentEquals(byteArrayOf(1,0))||value.contentEquals(byteArrayOf(0,0)))
            if(valid){if(value[0].toInt()==1){subscribed.add(d.address);StudioState.message("仪表已订阅 · 发送首个实时请求后开始回放")}else {subscribed.remove(d.address);disconnected.add(d.address)}}
            if(response)server?.sendResponse(d,id,if(valid)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
        }
        override fun onDescriptorReadRequest(d: BluetoothDevice,id: Int,offset: Int,descriptor: BluetoothGattDescriptor){
            server?.sendResponse(d,id,if(offset==0)0 else BluetoothGatt.GATT_INVALID_OFFSET,offset,if(d.address in subscribed)byteArrayOf(1,0)else byteArrayOf(0,0))
        }
        override fun onCharacteristicWriteRequest(d: BluetoothDevice,id: Int,c: BluetoothGattCharacteristic,prepared: Boolean,response: Boolean,offset: Int,value: ByteArray){
            val valid=c.uuid==uuid(0xfff1)&&!prepared&&offset==0&&d.address in subscribed
            val accepted=valid&&requests.offer(d to value.copyOf())
            if(response)server?.sendResponse(d,id,if(accepted)0 else BluetoothGatt.GATT_FAILURE,offset,null)
            if(valid&&!accepted){StudioState.message("回放请求队列已满，断开该仪表");server?.cancelConnection(d)}
        }
        override fun onNotificationSent(d: BluetoothDevice,status: Int){notifications.offer(status)}
    }
    private fun waitFor(expected: String){val deadline=System.nanoTime()+15000000000
        while(System.nanoTime()<deadline){if(cancelled())throw InterruptedException();val e=events.poll(100,TimeUnit.MILLISECONDS)?:continue;check(!e.startsWith("ERROR")){e};if(e==expected)return};error("蓝牙回放启动超时：$expected")}
    fun run(){
        check(adapter.isEnabled){"请先开启蓝牙"}
        check(adapter.isMultipleAdvertisementSupported){"此手机不支持 BLE 外设广播，无法回放到仪表"}
        server=manager.openGattServer(context,callback)?:error("无法创建 GATT 外设服务")
        val rx=BluetoothGattCharacteristic(uuid(0xfff1),BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,BluetoothGattCharacteristic.PERMISSION_WRITE)
        tx.addDescriptor(BluetoothGattDescriptor(uuid(0x2902),BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service=BluetoothGattService(uuid(0xfff0),BluetoothGattService.SERVICE_TYPE_PRIMARY);service.addCharacteristic(rx);service.addCharacteristic(tx)
        check(server!!.addService(service)){"GATT 服务添加失败"};waitFor("service")
        val advertiser=adapter.bluetoothLeAdvertiser?:error("手机蓝牙广播不可用")
        advertiser.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(uuid(0xfff0))).build(),
            AdvertiseData.Builder().setIncludeDeviceName((adapter.name?:"").toByteArray(Charsets.UTF_8).size<=26).build(),advertiseCallback)
        waitFor("ready");StudioState.message("回放就绪 · 在仪表 BLE SCAN 页面选择此手机（FFF0）")
        while(!cancelled()) {
            disconnected.toList().forEach{engines.remove(it);disconnected.remove(it)}
            val (device,bytes)=requests.poll(100,TimeUnit.MILLISECONDS)?:continue
            if(device.address !in subscribed)continue
            if(disconnected.remove(device.address))engines.remove(device.address)
            val engine=engines.getOrPut(device.address){ReplayElm(tape,speed,loop){System.nanoTime()/1e9}}
            try {
                for(reply in engine.feed(bytes)){
                    StudioState.bytes(reply.bytes.size)
                    if(!norm(reply.command).startsWith("AT")&&reply.t!=null)StudioState.reply(reply.command,reply.bytes.toString(Charsets.US_ASCII),reply.t,sampled=reply.sampled?:Double.NEGATIVE_INFINITY,clientEpoch=device.address+"/"+System.identityHashCode(engine)+"/"+reply.cycle)
                    for(part in reply.bytes.toList().chunked(20)){
                        if(cancelled())throw InterruptedException();check(device.address in subscribed){"仪表断开"}
                        notifications.clear();tx.value=part.toByteArray()
                        check(server!!.notifyCharacteristicChanged(device,tx,false)){"通知发送未开始"}
                        val result=notifications.poll(5,TimeUnit.SECONDS)?:error("通知发送超时")
                        check(result==0){"通知失败：$result"}
                    }
                }
            }catch(e:InterruptedException){throw e}catch(e:Exception){StudioState.message("仪表回放错误：${e.message}");server?.cancelConnection(device);subscribed.remove(device.address);engines.remove(device.address)}
        }
    }
    override fun close(){adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback);server?.close();server=null;subscribed.clear()}
}
